package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.TrackMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * One search, all three catalogs, one validated stack of results.
 * <p>
 * The previous design tried engines in order and stopped at the first non-empty answer, which meant
 * the answer depended on which catalog happened to reply first rather than on which one actually had
 * the release — and "non-empty" was measured before anyone checked whether the results matched the
 * request at all. Here MusicBrainz, Discogs and Bandcamp run in parallel, everything they return is
 * put through {@link ReleaseMatchValidator}, and only confirmed matches make it into the stack.
 */
@Slf4j
@Service
public class AggregatedSearchService {

    /** Per-engine cap applied before merging, so one chatty catalog cannot crowd out the others. */
    private static final int PER_ENGINE_CAP = 12;
    private static final int TOTAL_CAP = 20;
    /** Tracklist lookups cost one API call each — this is the ceiling per search. */
    private static final int TRACKLIST_BUDGET = 8;
    /** Below this many confirmed matches a track query is worth spending the tracklist budget on. */
    private static final int DEEP_CHECK_THRESHOLD = 5;
    private static final int ENGINE_TIMEOUT_SECONDS = 25;

    /**
     * Which copy of a release survives deduplication. Discogs cards are the richest (label, format,
     * pressing tags) and its release page carries the per-track videos the 🎧 button uses; Bandcamp
     * is a direct listen/buy page; MusicBrainz has the best coverage but the thinnest card, so it
     * wins only when it is the sole source.
     */
    private static final List<SearchEngine> SOURCE_PRIORITY =
            List.of(SearchEngine.DISCOGS, SearchEngine.BANDCAMP, SearchEngine.MUSICBRAINZ);

    private final Map<SearchEngine, SearchEngineService> engines;
    private final Executor executor;

    public AggregatedSearchService(Map<SearchEngine, SearchEngineService> engines,
                                   @Qualifier("searchFanoutExecutor") Executor executor) {
        this.engines = engines;
        this.executor = executor;
    }

    /**
     * @param releases         confirmed matches, best first
     * @param rawCount         how much the engines returned before validation — a big number next to
     *                         an empty {@code releases} means the query was too vague, not unknown
     * @param sources          engines that contributed at least one confirmed match
     * @param tracklistsChecked how many extra tracklist lookups this search spent
     */
    public record Aggregated(List<ReleaseMetadata> releases, int rawCount,
                             List<SearchEngine> sources, int tracklistsChecked) {
        public boolean isEmpty() {
            return releases.isEmpty();
        }
    }

    /** Pinpoint search: only releases confirmed to be the requested artist/title/track come back. */
    public Aggregated search(MetadataSearchRequest request) {
        return run(request, true);
    }

    /** Everything the engines returned, unvalidated — the "копай глибше" escape hatch. */
    public Aggregated searchLoose(MetadataSearchRequest request) {
        return run(request, false);
    }

    private Aggregated run(MetadataSearchRequest request, boolean validate) {
        Map<SearchEngine, List<ReleaseMetadata>> raw = queryAllEngines(request);
        int rawCount = raw.values().stream().mapToInt(List::size).sum();

        if (!validate) {
            List<ReleaseMetadata> loose = raw.values().stream()
                    .flatMap(list -> list.stream().limit(PER_ENGINE_CAP))
                    .toList();
            List<ReleaseMetadata> merged = dedupe(loose).stream().limit(TOTAL_CAP).toList();
            return new Aggregated(merged, rawCount, sourcesOf(merged), 0);
        }

        List<Scored> scored = new ArrayList<>();
        List<ReleaseMetadata> undecided = new ArrayList<>();
        raw.forEach((engine, releases) -> releases.stream().limit(PER_ENGINE_CAP).forEach(release -> {
            var match = ReleaseMatchValidator.evaluate(request, release);
            if (match.isHit()) {
                scored.add(new Scored(release, match));
            } else if (ReleaseMatchValidator.needsTracklistCheck(request, release)) {
                undecided.add(release);
            }
        }));

        int checked = 0;
        if (scored.size() < DEEP_CHECK_THRESHOLD && !undecided.isEmpty()) {
            List<ReleaseMetadata> probe = undecided.stream().limit(TRACKLIST_BUDGET).toList();
            checked = probe.size();
            scored.addAll(confirmByTracklist(request, probe));
        }

        List<ReleaseMetadata> ranked = scored.stream()
                .sorted(Comparator.comparingInt((Scored s) -> s.match().ordinal())
                        .thenComparingInt(s -> sourceRank(s.release().source())))
                .map(Scored::release)
                .toList();
        List<ReleaseMetadata> merged = dedupe(ranked).stream().limit(TOTAL_CAP).toList();

        log.info("Aggregated search: raw={} confirmed={} tracklistsChecked={} artist='{}' release='{}' recording='{}'",
                rawCount, merged.size(), checked, request.artist(), request.release(), request.recording());
        return new Aggregated(merged, rawCount, sourcesOf(merged), checked);
    }

    private Map<SearchEngine, List<ReleaseMetadata>> queryAllEngines(MetadataSearchRequest request) {
        Map<SearchEngine, CompletableFuture<List<ReleaseMetadata>>> futures = new LinkedHashMap<>();
        engines.forEach((engine, service) -> futures.put(engine,
                CompletableFuture.supplyAsync(() -> service.searchReleases(request), executor)
                        .orTimeout(ENGINE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(e -> {
                            log.warn("Engine {} failed during aggregated search: {}", engine, e.getMessage());
                            return List.of();
                        })));

        Map<SearchEngine, List<ReleaseMetadata>> results = new LinkedHashMap<>();
        futures.forEach((engine, future) -> {
            List<ReleaseMetadata> releases = future.join();
            results.put(engine, releases == null ? List.of() : releases);
        });
        return results;
    }

    /**
     * Loads tracklists for candidates that could only be confirmed by what's on them — a compilation
     * whose title says nothing about the track that was asked for.
     */
    private List<Scored> confirmByTracklist(MetadataSearchRequest request, List<ReleaseMetadata> candidates) {
        List<CompletableFuture<Scored>> futures = candidates.stream()
                .map(release -> CompletableFuture.supplyAsync(() -> {
                    List<TrackMetadata> tracks = engines.get(release.source()).getTracks(release);
                    if (tracks == null || tracks.isEmpty()) return null;
                    ReleaseMetadata withTracks = release.withTracks(tracks);
                    var match = ReleaseMatchValidator.evaluate(request, withTracks);
                    return match.isHit() ? new Scored(withTracks, match) : null;
                }, executor).orTimeout(ENGINE_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(e -> {
                    log.warn("Tracklist check failed for {}: {}", release.id(), e.getMessage());
                    return null;
                }))
                .toList();

        return futures.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList();
    }

    /** Same album from two catalogs is one entry — the higher-priority source keeps the slot. */
    private static List<ReleaseMetadata> dedupe(List<ReleaseMetadata> releases) {
        LinkedHashMap<String, ReleaseMetadata> byIdentity = new LinkedHashMap<>();
        for (ReleaseMetadata release : releases) {
            String key = ReleaseMatchValidator.normalize(release.artist())
                    + "::" + ReleaseMatchValidator.normalize(release.title());
            ReleaseMetadata kept = byIdentity.get(key);
            if (kept == null || sourceRank(release.source()) < sourceRank(kept.source())) {
                byIdentity.put(key, release);
            }
        }
        return new ArrayList<>(byIdentity.values());
    }

    private static List<SearchEngine> sourcesOf(List<ReleaseMetadata> releases) {
        return releases.stream().map(ReleaseMetadata::source).distinct().toList();
    }

    private static int sourceRank(SearchEngine source) {
        int index = SOURCE_PRIORITY.indexOf(source);
        return index < 0 ? SOURCE_PRIORITY.size() : index;
    }

    private record Scored(ReleaseMetadata release, ReleaseMatchValidator.Match match) {}
}
