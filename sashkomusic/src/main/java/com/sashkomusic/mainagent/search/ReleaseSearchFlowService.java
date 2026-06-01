package com.sashkomusic.mainagent.search;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.util.ReleaseCardFormatter;
import com.sashkomusic.mainagent.shared.util.SearchUrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReleaseSearchFlowService {

    private final SearchRequestExtractor searchRequestExtractor;
    private final Map<SearchEngine, SearchEngineService> searchEngines;
    private final SearchContextService contextService;
    private final FileIdCacheService fileIdCacheService;

    public List<BotResponse> searchDefault(ConversationContext ctx, String rawInput) {
        var searchRequest = searchRequestExtractor.extract(rawInput);
        for (SearchEngine engine : SearchEngine.values()) {
            log.info("Trying to search in {}", engine);

            var releases = searchEngines.get(engine).searchReleases(searchRequest);
            if (!releases.isEmpty()) {
                contextService.saveSearchContext(ctx.conversationId(), engine, rawInput, searchRequest, releases);
                return buildPageResponse(ctx, 0);
            }
        }
        var buttons = buildEmptyResultsButtons(searchRequest);
        return List.of(BotResponse.withButtons("😔 нич не знайшов.", buttons));
    }

    public List<BotResponse> search(ConversationContext ctx, String rawInput, SearchEngine searchEngine) {
        log.info("Searching with engine: {}", searchEngine);
        var searchRequest = searchRequestExtractor.extract(rawInput);

        var engine = searchEngines.get(searchEngine);
        var releases = engine.searchReleases(searchRequest);

        contextService.saveSearchContext(ctx.conversationId(), searchEngine, rawInput, searchRequest, releases);

        if (releases.isEmpty()) {
            var buttons = buildEmptyResultsButtons(searchRequest);
            return List.of(BotResponse.withButtons("😔 нич не знайшов в тому %s.".formatted(engine.getName()), buttons));
        }
        return buildPageResponse(ctx, 0);
    }

    public List<BotResponse> switchStrategyAndSearch(ConversationContext ctx) {
        SearchEngine currentEngine = contextService.getSource(ctx.conversationId());
        String rawInput = contextService.getRawInput(ctx.conversationId());

        if (currentEngine == SearchEngine.MUSICBRAINZ) {
            return search(ctx, rawInput, SearchEngine.DISCOGS);
        } else if (currentEngine == SearchEngine.DISCOGS) {
            return search(ctx, rawInput, SearchEngine.BANDCAMP);
        } else {
            return List.of(BotResponse.text("😔 глибше нікуди, вшьо."));
        }
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

    public List<BotResponse> handleCardCallback(ConversationContext ctx, String callbackData, Integer messageId) {
        int index = Integer.parseInt(callbackData.substring("CARD:".length()));
        var releases = contextService.getSearchResults(ctx.conversationId());
        if (releases.isEmpty()) {
            return List.of(BotResponse.text("результатів вже нема."));
        }
        int safeIndex = Math.floorMod(index, releases.size());
        var release = releases.get(safeIndex);
        var rows = buildCardButtonRows(release, safeIndex, releases.size());
        String text = buildCardText(release, safeIndex, releases.size());
        String imageRef = fileIdCacheService.get(ctx.conversationId(), release.getCoverArtUrl())
                .map(fid -> "FILE_ID:" + fid)
                .orElse(release.getCoverArtUrl());

        if (messageId == null) {
            return List.of(BotResponse.cardWithRows(text, release.getCoverArtUrl(), rows));
        }
        return List.of(BotResponse.editCard(messageId, text, imageRef, rows));
    }

    public List<BotResponse> buildPageResponse(ConversationContext ctx, int page) {
        var releases = contextService.getSearchResults(ctx.conversationId());
        if (releases.isEmpty()) {
            return List.of(BotResponse.text("результатів немає."));
        }
        int index = Math.floorMod(page, releases.size());
        var release = releases.get(index);
        var rows = buildCardButtonRows(release, index, releases.size());
        String text = buildCardText(release, index, releases.size());
        return List.of(BotResponse.cardWithRows(text, release.getCoverArtUrl(), rows));
    }

    private String buildCardText(ReleaseMetadata release, int index, int total) {
        String body = ReleaseCardFormatter.formatCardText(release);
        return "📍 %d/%d\n%s".formatted(index + 1, total, body);
    }

    private List<List<BotResponse.ButtonDto>> buildCardButtonRows(ReleaseMetadata release, int index, int total) {
        int prev = Math.floorMod(index - 1, total);
        int next = Math.floorMod(index + 1, total);
        List<BotResponse.ButtonDto> row = new ArrayList<>();
        row.add(new BotResponse.ButtonDto("⬅️", "CARD:" + prev));
        row.add(new BotResponse.ButtonDto("🎧", "STREAM:" + release.id()));
        String releaseUrl = buildReleaseUrlForSource(release);
        if (releaseUrl != null) {
            row.add(new BotResponse.ButtonDto("🔗", releaseUrl));
        }
        row.add(new BotResponse.ButtonDto("⬇️", "DL:" + release.id()));
        row.add(new BotResponse.ButtonDto("➡️", "CARD:" + next));
        return List.of(row);
    }

    private String buildReleaseUrlForSource(ReleaseMetadata release) {
        try {
            SearchEngine engine = SearchEngine.valueOf(release.source().name());
            SearchEngineService service = searchEngines.get(engine);
            if (service != null) {
                String url = service.buildReleaseUrl(release);
                if (url != null && !url.isEmpty()) {
                    return "URL:" + url;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build release URL for source {}: {}", release.source(), e.getMessage());
        }
        return null;
    }

    private static LinkedHashMap<String, String> buildEmptyResultsButtons(MetadataSearchRequest searchRequest) {
        var buttons = new LinkedHashMap<String, String>();
        buttons.put("🎧", "STREAM:");
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
