package com.sashkomusic.mainagent.search;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.util.ReleaseCardFormatter;
import com.sashkomusic.mainagent.shared.util.SearchUrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReleaseSearchFlowService {

    private final SearchRequestExtractor searchRequestExtractor;
    private final Map<SearchEngine, SearchEngineService> searchEngines;
    private final AggregatedSearchService aggregatedSearch;
    private final SearchContextService contextService;
    private final FileIdCacheService fileIdCacheService;
    private final MetadataUrlFetcher metadataUrlFetcher;

    public List<BotResponse> showByUrl(ConversationContext ctx, String url) {
        Optional<ReleaseMetadata> result = metadataUrlFetcher.fetch(url);
        if (result.isEmpty()) {
            return List.of(BotResponse.text("😔 не вдалось знайти реліз за цим посиланням."));
        }
        return showResolvedRelease(ctx, result.get(), url);
    }

    /**
     * Shows an already-resolved release (e.g. matched via Google Vision image search) without
     * re-fetching it from the source engine.
     */
    public List<BotResponse> showResolvedRelease(ConversationContext ctx, ReleaseMetadata release, String rawInput) {
        MetadataSearchRequest request = new MetadataSearchRequest(
                null, release.artist(), release.title(), "", DateRange.empty(), "", "", "", "", "", "", "");
        contextService.saveSearchContext(ctx.conversationId(), release.source(), rawInput, request, List.of(release));
        return buildPageResponse(ctx, 0);
    }

    /**
     * One pinpoint search over all three catalogs at once, rendered as a single stack of cards.
     * Results that do not match what was asked for never reach the user — see
     * {@link AggregatedSearchService}.
     */
    public List<BotResponse> searchDefault(ConversationContext ctx, String rawInput) {
        var searchRequest = searchRequestExtractor.extract(rawInput);
        var aggregated = aggregatedSearch.search(searchRequest);

        if (aggregated.isEmpty()) {
            contextService.rememberQuery(ctx.conversationId(), rawInput, searchRequest);
            return List.of(BotResponse.withButtons(
                    emptyMessage(aggregated, rawInput), buildEmptyResultsButtons(searchRequest)));
        }
        contextService.beginStacks(ctx.conversationId());
        String stackId = contextService.openStack(ctx.conversationId(), rawInput, searchRequest, null, aggregated.releases());
        return buildStackResponse(ctx, stackId, 0);
    }

    /**
     * "⛏️ копай" — the same query with validation switched off, so everything the catalogs returned
     * is shown. The escape hatch for when the pinpoint filter was too strict (odd spelling, an
     * artist credited differently on the pressing).
     */
    public List<BotResponse> switchStrategyAndSearch(ConversationContext ctx) {
        String rawInput;
        MetadataSearchRequest searchRequest;
        try {
            rawInput = contextService.getRawInput(ctx.conversationId());
            searchRequest = contextService.getSearchRequest(ctx.conversationId());
        } catch (SearchSessionExpiredException e) {
            return List.of(BotResponse.text("нема що копати — спочатку пошукай щось."));
        }
        if (searchRequest == null) {
            searchRequest = searchRequestExtractor.extract(rawInput);
        }

        var aggregated = aggregatedSearch.searchLoose(searchRequest);
        if (aggregated.isEmpty()) {
            return List.of(BotResponse.text("😔 глибше нікуди, вшьо."));
        }
        contextService.beginStacks(ctx.conversationId());
        String stackId = contextService.openStack(ctx.conversationId(), rawInput, searchRequest,
                "усе підряд", aggregated.releases());
        return buildStackResponse(ctx, stackId, 0);
    }

    private static String emptyMessage(AggregatedSearchService.Aggregated aggregated, String rawInput) {
        if (aggregated.rawCount() == 0) {
            return "😔 нич не знайшов.";
        }
        // Plenty of results, none of them the requested thing — that is a different problem from
        // "does not exist", and the user can act on it (⛏️ shows the pile anyway).
        return "😔 знайшов %d результатів, але жоден не збігся з «%s». уточни запит або тисни ⛏️."
                .formatted(aggregated.rawCount(), rawInput);
    }

    public SearchResult searchWithFallback(String query, SearchEngine... engines) {
        var searchRequest = searchRequestExtractor.extract(query);

        for (SearchEngine engine : engines) {
            log.info("Trying to search in {}", engine);
            var searchEngineService = searchEngines.get(engine);
            var releases = searchEngineService.searchReleases(searchRequest);

            if (!releases.isEmpty()) {
                log.info("Found {} releases in {}", releases.size(), engine);
                return new SearchResult(releases, engine, searchRequest);
            }
        }

        log.warn("No releases found in any engine for query: {}", query);
        return new SearchResult(List.of(), null, searchRequest);
    }

    public record SearchResult(List<ReleaseMetadata> releases, SearchEngine engine,
                               MetadataSearchRequest searchRequest) {
    }

    /**
     * {@code CARD:<index>} pages the active stack; {@code CARD:<stackId>:<index>} pages one specific
     * stack, which is what makes several stacks scrollable independently. The short form is still
     * accepted — cards sent before multi-stack results existed carry it.
     */
    public List<BotResponse> handleCardCallback(ConversationContext ctx, String callbackData, Integer messageId) {
        String payload = callbackData.substring("CARD:".length());
        int separator = payload.lastIndexOf(':');
        String stackId = separator < 0 ? null : payload.substring(0, separator);
        int index = Integer.parseInt(payload.substring(separator + 1));

        var stack = resolveStack(ctx, stackId);
        if (stack.releases().isEmpty()) {
            return List.of(BotResponse.text("результатів вже нема."));
        }
        int safeIndex = Math.floorMod(index, stack.releases().size());
        rememberPage(ctx, stack, safeIndex);

        var release = stack.releases().get(safeIndex);
        var rows = buildCardButtonRows(release, stack, safeIndex);
        String text = buildCardText(release, safeIndex, stack.releases().size(), stack.label());
        String imageRef = fileIdCacheService.get(ctx.conversationId(), release.getCoverArtUrl())
                .map(fid -> "FILE_ID:" + fid)
                .orElse(release.getCoverArtUrl());

        if (messageId == null) {
            return List.of(BotResponse.cardWithRows(text, release.getCoverArtUrl(), rows));
        }
        return List.of(BotResponse.editCard(messageId, text, imageRef, rows));
    }

    public List<BotResponse> buildPageResponse(ConversationContext ctx, int page) {
        return buildStackResponse(ctx, null, page);
    }

    /** Renders one card of one stack. {@code stackId == null} means the active stack. */
    public List<BotResponse> buildStackResponse(ConversationContext ctx, String stackId, int page) {
        var stack = resolveStack(ctx, stackId);
        if (stack.releases().isEmpty()) {
            return List.of(BotResponse.text("результатів немає."));
        }
        int index = Math.floorMod(page, stack.releases().size());
        rememberPage(ctx, stack, index);

        var release = stack.releases().get(index);
        var rows = buildCardButtonRows(release, stack, index);
        String text = buildCardText(release, index, stack.releases().size(), stack.label());
        return List.of(BotResponse.cardWithRows(text, release.getCoverArtUrl(), rows));
    }

    /** Falls back to the active stack when the id is unknown — e.g. a card from a previous turn. */
    private SearchStack resolveStack(ConversationContext ctx, String stackId) {
        if (stackId != null) {
            var found = contextService.findStack(ctx.conversationId(), stackId);
            if (found.isPresent()) return found.get();
        }
        return new SearchStack(stackId, null, contextService.getSearchResults(ctx.conversationId()), 0);
    }

    private void rememberPage(ConversationContext ctx, SearchStack stack, int index) {
        if (stack.id() != null) {
            contextService.updateStackPage(ctx.conversationId(), stack.id(), index);
        }
        // getTrackList / findSimilar read "the release in view" from the active stack's page.
        contextService.updateCurrentPage(ctx.conversationId(), index);
    }

    private String buildCardText(ReleaseMetadata release, int index, int total, String label) {
        String body = ReleaseCardFormatter.formatCardText(release);
        String source = switch (release.source()) {
            case MUSICBRAINZ -> "mb";
            case DISCOGS -> "discogs";
            case BANDCAMP -> "bandcamp";
        };
        String releaseUrl = buildReleaseUrlForSource(release);
        String origin = releaseUrl == null ? source : source + " [🔗](" + releaseUrl + ")";
        // The label tells three stacks in a row apart — which recommendation this pile answers.
        String suffix = label == null || label.isBlank() ? "" : " · " + label;
        return "📍 %d/%d (%s)%s\n%s".formatted(index + 1, total, origin, suffix, body);
    }

    private List<List<BotResponse.ButtonDto>> buildCardButtonRows(ReleaseMetadata release, SearchStack stack, int index) {
        int total = stack.releases().size();
        String prefix = stack.id() == null ? "CARD:" : "CARD:" + stack.id() + ":";
        List<BotResponse.ButtonDto> row = new ArrayList<>();
        row.add(new BotResponse.ButtonDto("⬅️", prefix + Math.floorMod(index - 1, total)));
        row.add(new BotResponse.ButtonDto("🎧", "STREAM:" + release.id()));
        row.add(new BotResponse.ButtonDto("⬇️", "DL:" + release.id()));
        row.add(new BotResponse.ButtonDto("➡️", prefix + Math.floorMod(index + 1, total)));
        return List.of(row);
    }

    private String buildReleaseUrlForSource(ReleaseMetadata release) {
        try {
            SearchEngine engine = SearchEngine.valueOf(release.source().name());
            SearchEngineService service = searchEngines.get(engine);
            if (service != null) {
                String url = service.buildReleaseUrl(release);
                if (url != null && !url.isEmpty()) {
                    return url;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build release URL for source {}: {}", release.source(), e.getMessage());
        }
        return null;
    }

    private static LinkedHashMap<String, String> buildEmptyResultsButtons(MetadataSearchRequest searchRequest) {
        var buttons = new LinkedHashMap<String, String>();
        buttons.put("💿", SearchUrlUtils.buildDiscogsSearchUrl(searchRequest.artist(), searchRequest.getTitle()));
        buttons.put("⛏️", "DIG_DEEPER");
        return buttons;
    }

    public BotResponse buildReleaseDownloadCard(ReleaseMetadata release, SearchEngine engine) {
        String cardText = ReleaseCardFormatter.formatCardText(release);

        var buttons = new LinkedHashMap<String, String>();
        String releaseUrl = searchEngines.get(engine).buildReleaseUrl(release);
        if (releaseUrl != null) {
            String buttonLabel = switch (engine) {
                case MUSICBRAINZ -> "🎵 musicbrainz";
                case DISCOGS -> "💿 discogs";
                case BANDCAMP -> "📼 bandcamp";
                default -> "🔗 link";
            };
            buttons.put(buttonLabel, "URL:" + releaseUrl);
        }

        return BotResponse.card(cardText, release.getCoverArtUrl(), buttons.isEmpty() ? null : buttons);
    }
}
