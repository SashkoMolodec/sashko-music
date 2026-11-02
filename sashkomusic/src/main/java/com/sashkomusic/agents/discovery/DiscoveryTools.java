package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.discovery.RelatedArtistsCollector.Connection;
import com.sashkomusic.agents.discovery.RelatedArtistsCollector.RelatedArtist;
import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchStack;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * MainAgent's discovery tools — search, recommend, similar, research. Each tool reads its conversation
 * from the {@link AgentTurn} and opens card stacks that {@code MainAgentService} sends after the reply.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscoveryTools {

    /** Three stacks is what still reads as an answer in a Telegram chat rather than as a dump. */
    private static final int MAX_RECOMMENDATIONS = 3;
    /** A ceiling, not a target: "схоже" shows what the catalogs confirm, up to this many stacks. */
    private static final int MAX_SIMILAR_STACKS = 10;
    /** A couple of spares for picks the catalogs don't have, without paying for the whole scene. */
    private static final int MAX_SIMILAR_ARTISTS_TRIED = 12;
    /** MusicBrainz serves one request a second, so more threads would only queue on its rate limiter. Shared by explore and similar. */
    private static final int SIMILAR_SEARCH_CONCURRENCY = 3;
    private static final int MAX_LABEL_STACKS = 10;
    /** Beyond three styles the recommendation topic stops describing anything in particular. */
    private static final int MAX_STYLE_SEEDS = 3;
    /** One ping per turn, however many searches the model runs in it. */
    private static final String SEARCHING = "🔍 шукаю...";

    private final AggregatedSearchService aggregatedSearch;
    private final SearchContextService searchContextService;
    private final SearchRequestExtractor searchRequestExtractor;
    private final ReleaseRecommender releaseRecommender;
    private final MusicBrainzClient musicBrainzClient;
    private final RelatedArtistsCollector relatedArtistsCollector;
    private final MusicResearcher musicResearcher;

    @Tool("""
            Pinpoint search for ONE specific thing — a release, a track, or an artist's records.
            Queries MusicBrainz, Discogs and Bandcamp simultaneously and keeps only results that are
            actually the artist/title asked for; vague matches are dropped rather than shown.
            Each call builds its own card stack, so call it once per release you want to show
            (at most 3 per answer). Resolve the query from the conversation first: if the user says
            "того самого артиста, але 90-ті", pass "<that artist> 1990-1999", never a pronoun.
            If it returns nothing, call it again with fewer filters — drop year/label/style first.
            For releases of one record label pass "лейбл <name>": that shows up to 10 separate stacks.
            """)
    public String search(
            @P("resolved query: artist + title (+ optional year/label), e.g. 'Adjust (BE) Mist'") String query,
            InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        turn.progress(SEARCHING);
        MetadataSearchRequest request = extractRequest(query);

        if (request.isLabelLookup()) {
            return searchLabel(turn, request, query);
        }

        // The lookup-vs-explore split is a routing decision the model makes, and it gets it wrong:
        // "пошукай detroit techno класичне" has no artist and no title, so there is nothing to
        // validate a result against and the answer degrades into the scattershot pile this whole
        // design exists to kill. Catch it here rather than trusting the prompt, and answer it the
        // way an explore ask should be answered — researched picks, each its own stack.
        if (!request.hasLookupAnchor()) {
            log.info("search('{}') has no artist/title anchor — handling as an explore ask", query);
            return explore(turn, query);
        }
        return runStack(turn, request, query);
    }

    @Tool("""
            Recommend NEW music for an open-ended ask — "порадь щось", "що послухати з детройт-техно",
            "хочу дарк ембієнт 90-х". Researches concrete releases on the web first, then runs the
            pinpoint search for each one, so the user gets up to 3 separate card stacks.
            Do NOT recommend releases from your own memory instead of calling this.
            """)
    public String exploreAndRecommend(
            @P("what to recommend, e.g. 'класичне detroit techno, витоки'") String topic,
            InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        turn.progress(SEARCHING);
        return explore(turn, topic);
    }

    private String explore(AgentTurn turn, String topic) {
        List<Candidate> candidates = researchCandidates(topic);
        if (candidates.isEmpty()) {
            return "research returned no concrete releases for '" + topic + "' — ask the user to narrow the topic";
        }
        return buildStacks(turn, candidates, "researched picks for '" + topic + "'");
    }

    @Tool("""
            Find releases similar to a given artist/release, or to the release currently in view — for
            "хочу схоже на X", "рекомендуй щось подібне", "similar to X". Walks the real connections
            around the seed — members, aliases, bandmates — plus ListenBrainz co-listen data, then
            confirms one release per related artist in the catalogs. The user gets up to 10 card
            stacks, each showing how it is connected. Never answer such asks from memory.
            """)
    public String findSimilar(
            @P("artist or release name to find similar music to, or empty to use the release currently in view") String seedQuery,
            InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        turn.progress(SEARCHING);
        String seed;
        MetadataSearchRequest seedRequest;
        if (seedQuery == null || seedQuery.isBlank()) {
            ReleaseMetadata current = currentRelease(turn.conversationId());
            if (current == null) {
                return "no release in view and no seed given — search for something first or name an artist";
            }
            seed = current.artist() + " — " + current.title();
            // The catalog already split artist from title — no extractor call needed for this one.
            seedRequest = new MetadataSearchRequest(null, current.artist(), current.title(), "",
                    DateRange.empty(), "", "", "", "", "", "", "");
        } else {
            seed = seedQuery.trim();
            // A typed seed arrives as the user said it — often "Artist — Title". MusicBrainz resolves
            // artists, not that whole string, so pull the artist out of it first.
            seedRequest = extractRequest(seed);
        }
        String seedArtist = seedRequest.artist().isBlank() ? seed : seedRequest.artist();

        List<RelatedArtist> related = musicBrainzClient.findArtistMbid(seedArtist)
                .map(relatedArtistsCollector::collect)
                .orElse(List.of());

        List<SearchStack> opened = openSimilarStacks(turn, seedArtist, related);
        if (opened.isEmpty()) {
            log.info("No confirmed related releases for '{}' — falling back to the seed's own styles", seedArtist);
            return similarByStyle(turn, seed, seedRequest);
        }
        return report(opened, "artists connected to " + seedArtist + " by MusicBrainz relations and ListenBrainz co-listen data");
    }

    /**
     * Confirms one release per related artist and opens a stack for each. The ranking decides who
     * makes the ceiling; on screen they go by kind of connection, so the cards follow the intro.
     * The searches run side by side because ten in a row would hold the bot for a minute; the
     * stacks are opened afterwards because openStack is a read-modify-write of the chat state.
     */
    private List<SearchStack> openSimilarStacks(AgentTurn turn, String seedArtist, List<RelatedArtist> related) {
        List<ConfirmedRelease> confirmed;
        try (ExecutorService pool = Executors.newFixedThreadPool(SIMILAR_SEARCH_CONCURRENCY)) {
            List<CompletableFuture<ConfirmedRelease>> searches = related.stream()
                    .limit(MAX_SIMILAR_ARTISTS_TRIED)
                    .map(artist -> CompletableFuture.supplyAsync(() -> confirmRelease(artist), pool)
                            .exceptionally(e -> {
                                log.warn("Related artist '{}' could not be confirmed: {}", artist.name(), e.getMessage());
                                return null;
                            }))
                    .toList();
            confirmed = searches.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .limit(MAX_SIMILAR_STACKS)
                    .sorted(Comparator.comparing(ConfirmedRelease::connection))
                    .toList();
        }

        List<SearchStack> opened = new ArrayList<>();
        for (ConfirmedRelease release : confirmed) {
            opened.add(openStack(turn, release.label(), release.request(),
                    release.label(), release.connection().describe(seedArtist), release.releases()));
        }
        return opened;
    }

    /** The related artist's main release, if it has one and the catalogs back it up; otherwise null. */
    private ConfirmedRelease confirmRelease(RelatedArtist artist) {
        Optional<String> title = relatedArtistsCollector.mainRelease(artist.mbid());
        if (title.isEmpty()) {
            log.info("Related artist '{}' has no release to show — skipped", artist.name());
            return null;
        }
        MetadataSearchRequest request = releaseRequest(artist.name(), title.get());
        var aggregated = aggregatedSearch.search(request);
        if (aggregated.isEmpty()) {
            log.info("Related release '{} — {}' had no catalog match — skipped", artist.name(), title.get());
            return null;
        }
        String label = "%s — %s · %s".formatted(artist.name(), title.get(), artist.reason());
        return new ConfirmedRelease(label, artist.connection(), request, aggregated.releases());
    }

    /**
     * One stack per artist, so the answer shows the label's roster rather than one prolific artist.
     * The validator has already dropped everything that is not really on this label.
     */
    private String searchLabel(AgentTurn turn, MetadataSearchRequest request, String query) {
        var aggregated = aggregatedSearch.search(request);
        if (aggregated.isEmpty()) {
            return reportMiss(turn, request, query, aggregated);
        }
        Map<String, ReleaseMetadata> byArtist = new LinkedHashMap<>();
        aggregated.releases().forEach(release ->
                byArtist.putIfAbsent(release.artist().toLowerCase(Locale.ROOT), release));

        // The cards wear the label's name; the intro needs to know which record each stack is.
        List<SearchStack> named = byArtist.values().stream()
                .limit(MAX_LABEL_STACKS)
                .map(release -> {
                    SearchStack stack = openStack(turn, query, request, request.label(), null, List.of(release));
                    return new SearchStack(stack.id(), release.artist() + " — " + release.title(), null, stack.releases(), 0);
                })
                .toList();
        return report(named, "releases on the label " + request.label());
    }

    /**
     * MusicBrainz and ListenBrainz cover what a lot of people listen to; an obscure 1993 white-label
     * 12" exists only on Discogs — together with its styles. So when co-listen data runs out, ask the
     * catalogs what the seed itself sounds like and recommend from that, instead of handing the model
     * a dead end it can only turn into "уточни назву".
     */
    private String similarByStyle(AgentTurn turn, String seed, MetadataSearchRequest seedRequest) {
        var aggregated = aggregatedSearch.search(seedRequest);
        Optional<ReleaseMetadata> top = aggregated.releases().stream().findFirst();
        if (top.isEmpty()) {
            return "no similarity data and no catalog match for '" + seed
                    + "' — ask the user to confirm the spelling";
        }

        List<String> styles = top.get().tags() == null ? List.of() : top.get().tags().stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .limit(MAX_STYLE_SEEDS)
                .toList();
        if (styles.isEmpty()) {
            return "'" + seed + "' exists in the catalogs but carries no genre/style tags, and ListenBrainz "
                    + "has no co-listen data for it — ask the user which direction they want";
        }

        String topic = String.join(" ", styles) + decadeOf(top.get()).map(d -> " " + d).orElse("");
        log.info("Style fallback for '{}': recommending on '{}'", seed, topic);
        return explore(turn, topic);
    }

    /** "1993" → "1990s" — a decade is a usable recommendation filter, an exact year is not. */
    private static Optional<String> decadeOf(ReleaseMetadata release) {
        if (release.years() == null) return Optional.empty();
        return release.years().stream()
                .filter(year -> year != null && year.matches("\\d{4}"))
                .map(Integer::parseInt)
                .min(Integer::compareTo)
                .map(year -> (year / 10 * 10) + "s");
    }

    @Tool("""
            Widen the last search — same query, validation switched off, so everything the catalogs
            returned is shown. Use when the user says "копай", "ще копай", "покажи все", "dig deeper",
            i.e. the pinpoint answer was too narrow or missed a differently-spelled pressing.
            """)
    public String digDeeper(InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        turn.progress(SEARCHING);
        String conversationId = turn.conversationId();
        MetadataSearchRequest request;
        String lastQuery;
        try {
            lastQuery = searchContextService.getRawInput(conversationId);
            request = searchContextService.getSearchRequest(conversationId);
        } catch (Exception e) {
            return "нема попереднього пошуку — спочатку знайди щось";
        }
        if (request == null) {
            request = extractRequest(lastQuery);
        }

        var aggregated = aggregatedSearch.searchLoose(request);
        if (aggregated.isEmpty()) {
            return "nothing at all on any source for '" + lastQuery + "'";
        }
        openStack(turn, lastQuery, request, "усе підряд", null, aggregated.releases());
        return "showed 1 card stack with %d unfiltered results for '%s'".formatted(aggregated.releases().size(), lastQuery);
    }

    @Tool("Get the track list of the release the user is currently viewing. Use when the user asks about tracks, tracklist, or song names of the current release.")
    public String getTrackList(InvocationParameters parameters) {
        String conversationId = AgentTurn.of(parameters).conversationId();
        List<ReleaseMetadata> results;
        try {
            results = searchContextService.getSearchResults(conversationId);
        } catch (Exception e) {
            return "no release context — search for a release first";
        }
        if (results.isEmpty()) return "no release context — search for a release first";

        int page = searchContextService.getCurrentPage(conversationId);
        ReleaseMetadata r = results.get(Math.min(page, results.size() - 1));
        ReleaseMetadata withTracks = searchContextService.getMetadataWithTracks(r.id(), conversationId);
        if (withTracks != null) r = withTracks;

        if (r.tracks() == null || r.tracks().isEmpty()) {
            return "track list not available for this release";
        }
        String tracks = r.tracks().stream()
                .map(t -> t.number() + ". " + t.title())
                .collect(Collectors.joining("\n"));
        return "%s — %s (%s)\n%s".formatted(r.artist(), r.title(), r.getYearsDisplay(), tracks);
    }

    @Tool("""
            Answer a factual music question from the web — artist bios, label history, discographies,
            scenes: "розкажи про X", "хто такий X", "що за лейбл Y", "коли вийшов Z". Use it when the user
            wants to KNOW something and did not ask to find or show releases. Pass a self-contained
            question with every name spelled out — resolve "він" / "цей лейбл" from the conversation first.
            Returns a short sourced answer to relay; it shows no cards.
            """)
    public String researchMusic(
            @P("self-contained question, names spelled out, e.g. 'history of the Tresor label'") String question,
            InvocationParameters parameters) {
        AgentTurn.of(parameters).progress("🌐 шукаю в мережі...");
        try {
            String answer = musicResearcher.research(question);
            return answer == null || answer.isBlank()
                    ? "web research found nothing on '" + question + "' — say so"
                    : answer;
        } catch (Exception e) {
            log.warn("Music research failed for '{}': {}", question, e.getMessage());
            return "web research failed — tell the user you could not check this right now";
        }
    }

    /** One recommended thing to look up: the pinpoint search's input plus the label its stack wears. */
    private record Candidate(String artist, String title, String label) {}

    /** A researched pick the catalogs confirmed, ready to become a stack. */
    private record ConfirmedPick(Candidate candidate, MetadataSearchRequest request, List<ReleaseMetadata> releases) {}

    /** A related artist's release the catalogs confirmed, ready to become a stack. */
    private record ConfirmedRelease(String label, Connection connection, MetadataSearchRequest request,
                                    List<ReleaseMetadata> releases) {}

    private List<Candidate> researchCandidates(String topic) {
        String answer;
        try {
            answer = releaseRecommender.recommend(topic);
        } catch (Exception e) {
            log.warn("Release recommender failed for topic '{}': {}", topic, e.getMessage());
            return List.of();
        }
        if (answer == null || answer.isBlank()) return List.of();

        List<Candidate> candidates = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            // The model is asked for "Artist — Title" lines; anything else on the line is not a pick.
            String[] parts = line.trim().split("\\s*[—–]\\s*|\\s+-\\s+", 2);
            if (parts.length != 2) continue;
            String artist = parts[0].replaceFirst("^\\d+[.)]\\s*", "").trim();
            String title = parts[1].trim();
            if (artist.isBlank() || title.isBlank()) continue;
            candidates.add(new Candidate(artist, title, artist + " — " + title));
        }
        return candidates;
    }

    /**
     * Turns researched picks into one card stack each. Candidates that nothing in the catalogs backs
     * up are skipped silently — a recommendation the user cannot open is worse than one fewer stack.
     * The recommender lists spares for exactly that case; all picks are searched side by side and the
     * first confirmed ones, in the recommender's order, become the stacks.
     */
    private String buildStacks(AgentTurn turn, List<Candidate> candidates, String provenance) {
        List<ConfirmedPick> confirmed;
        try (ExecutorService pool = Executors.newFixedThreadPool(SIMILAR_SEARCH_CONCURRENCY)) {
            List<CompletableFuture<ConfirmedPick>> searches = candidates.stream()
                    .map(candidate -> CompletableFuture.supplyAsync(() -> confirmPick(candidate), pool)
                            .exceptionally(e -> {
                                log.warn("Recommendation '{}' could not be confirmed: {}", candidate.label(), e.getMessage());
                                return null;
                            }))
                    .toList();
            confirmed = searches.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .limit(MAX_RECOMMENDATIONS)
                    .toList();
        }

        if (confirmed.isEmpty()) {
            return "none of the researched picks could be confirmed in the catalogs — say so and ask for a narrower ask";
        }
        List<SearchStack> opened = new ArrayList<>();
        for (ConfirmedPick pick : confirmed) {
            opened.add(openStack(turn, pick.candidate().label(), pick.request(),
                    pick.candidate().label(), null, pick.releases()));
        }
        return report(opened, provenance);
    }

    private ConfirmedPick confirmPick(Candidate candidate) {
        MetadataSearchRequest request = releaseRequest(candidate.artist(), candidate.title());
        var aggregated = aggregatedSearch.search(request);
        if (aggregated.isEmpty()) {
            log.info("Recommendation '{}' had no catalog match — skipped", candidate.label());
            return null;
        }
        return new ConfirmedPick(candidate, request, aggregated.releases());
    }

    private static MetadataSearchRequest releaseRequest(String artist, String title) {
        return new MetadataSearchRequest(null, artist, title, "", DateRange.empty(),
                "", "", "", "", "", "", "");
    }

    /**
     * Opens one stack in this turn. The first stack a turn opens first drops the previous answer's
     * stacks; a turn that finds nothing leaves them, so paging on cards already in the chat keeps working.
     */
    private SearchStack openStack(AgentTurn turn, String rawInput, MetadataSearchRequest request,
                                  String label, String group, List<ReleaseMetadata> releases) {
        if (turn.claimStackReset()) {
            searchContextService.beginStacks(turn.conversationId());
        }
        String id = searchContextService.openStack(turn.conversationId(), rawInput, request, label, group, releases);
        SearchStack stack = new SearchStack(id, label, group, releases, 0);
        turn.stackOpened(stack);
        return stack;
    }

    private static String report(List<SearchStack> stacks, String provenance) {
        String facts = stacks.size() == 1
                ? "showed 1 card stack — %s: %s".formatted(stacks.getFirst().label(), StackReport.describe(stacks.getFirst().releases()))
                : StackReport.forMainAgent(stacks);
        return facts + "\nSource: " + provenance + ".";
    }

    private String reportMiss(AgentTurn turn, MetadataSearchRequest request, String rawInput,
                              AggregatedSearchService.Aggregated aggregated) {
        // Keep the query around even though it confirmed nothing — digDeeper widens THIS query.
        searchContextService.rememberQuery(turn.conversationId(), rawInput, request);
        String filters = describeFilters(request);
        if (aggregated.rawCount() > 0) {
            return ("catalogs returned %d results for '%s' but none were the requested artist/title/label "
                    + "(filters: %s). Retry search() with fewer filters, or confirm the spelling with the user.")
                    .formatted(aggregated.rawCount(), rawInput, filters);
        }
        return "nothing found for '%s' (filters: %s). Retry search() with fewer filters — drop year/label/style first."
                .formatted(rawInput, filters);
    }

    private String runStack(AgentTurn turn, MetadataSearchRequest request, String rawInput) {
        var aggregated = aggregatedSearch.search(request);
        if (aggregated.isEmpty()) {
            return reportMiss(turn, request, rawInput, aggregated);
        }
        openStack(turn, rawInput, request, null, null, aggregated.releases());
        String sources = aggregated.sources().stream().map(SearchEngine::getName).collect(Collectors.joining(", "));
        return "showed 1 card stack for '%s' on %s: %s"
                .formatted(rawInput, sources, StackReport.describe(aggregated.releases()));
    }

    private static String describeFilters(MetadataSearchRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.artist().isEmpty()) parts.add("artist=" + request.artist());
        if (!request.release().isEmpty()) parts.add("release=" + request.release());
        if (!request.recording().isEmpty()) parts.add("track=" + request.recording());
        if (request.dateRange() != null && !request.dateRange().isEmpty()) parts.add("year=" + request.dateRange().toDiscogsParam());
        if (!request.style().isEmpty()) parts.add("style=" + request.style());
        if (!request.label().isEmpty()) parts.add("label=" + request.label());
        if (!request.country().isEmpty()) parts.add("country=" + request.country());
        if (!request.format().isEmpty()) parts.add("format=" + request.format());
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    /** The release the user is looking at right now — the active stack's current page. */
    private ReleaseMetadata currentRelease(String conversationId) {
        try {
            List<ReleaseMetadata> results = searchContextService.getSearchResults(conversationId);
            if (results.isEmpty()) return null;
            int page = searchContextService.getCurrentPage(conversationId);
            return results.get(Math.min(page, results.size() - 1));
        } catch (Exception e) {
            return null;
        }
    }

    private MetadataSearchRequest extractRequest(String query) {
        try {
            // The extractor is an LLM and two of its rules never hold: it reads the Discogs artist
            // disambiguator "(BE)" as a country filter, and it fills only `release` for a bare
            // "Artist — Title" — which silently disables every track-lookup path downstream.
            // SearchRequestNormalizer settles both in code.
            return SearchRequestNormalizer.normalize(query, searchRequestExtractor.extract(query));
        } catch (Exception e) {
            log.warn("SearchRequestExtractor failed for query '{}': {} — falling back to raw query", query, e.getMessage());
            return new MetadataSearchRequest(null, query, "", "", DateRange.empty(), "", "", "", "", "", "", "");
        }
    }
}
