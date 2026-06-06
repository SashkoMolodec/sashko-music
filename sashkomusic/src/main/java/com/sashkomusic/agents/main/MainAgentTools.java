package com.sashkomusic.agents.main;

import com.sashkomusic.agents.bridge.ProgressNotifier;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
import com.sashkomusic.agents.contract.DownloadRequest;
import com.sashkomusic.agents.contract.DownloadResult;
import com.sashkomusic.agents.discovery.DiscoveryAgentService;
import com.sashkomusic.agents.download.DownloadAgentService;
import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MainAgentTools {

    private final DiscoveryAgentService discoveryAgent;
    private final DownloadAgentService downloadAgent;
    private final ProgressNotifier progressNotifier;
    private final SearchContextService searchContextService;
    private final LibrarySearchService librarySearchService;

    @Tool("Search for music — artist, album or track. Use for free-form discovery requests. Tries MusicBrainz → Discogs → Bandcamp, stops at first hit.")
    public String findMusic(
            @P("user's full query, e.g. 'Burial new album', 'Aphex Twin discography'") String query,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "🔍 шукаю...");
        return runDiscovery(DiscoverRequest.of(conversationId, query));
    }

    @Tool("Search for music specifically on Discogs. Use when user explicitly mentions Discogs or asks to search there.")
    public String findMusicOnDiscogs(
            @P("user's search query") String query,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "🔍 шукаю на Discogs...");
        return runDiscovery(DiscoverRequest.of(conversationId, query, SearchEngine.DISCOGS));
    }

    @Tool("Search for music specifically on Bandcamp. Use when user explicitly mentions Bandcamp or asks to search there.")
    public String findMusicOnBandcamp(
            @P("user's search query") String query,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "🔍 шукаю на Bandcamp...");
        return runDiscovery(DiscoverRequest.of(conversationId, query, SearchEngine.BANDCAMP));
    }

    @Tool("Search for music specifically on MusicBrainz. Use when user explicitly mentions MusicBrainz or asks to search there.")
    public String findMusicOnMusicBrainz(
            @P("user's search query") String query,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "🔍 шукаю на MusicBrainz...");
        return runDiscovery(DiscoverRequest.of(conversationId, query, SearchEngine.MUSICBRAINZ));
    }

    @Tool("Dig deeper — search the same query on the next source. Use when user says 'копай', 'ще копай', 'try another source', 'dig deeper'.")
    public String digDeeper(@ToolMemoryId String conversationId) {
        String lastQuery;
        SearchEngine lastEngine;
        try {
            lastQuery = searchContextService.getRawInput(conversationId);
            lastEngine = searchContextService.getSource(conversationId);
        } catch (Exception e) {
            return "нема попереднього пошуку — спочатку знайди щось";
        }
        SearchEngine[] engines = SearchEngine.values();
        SearchEngine nextEngine = engines[(lastEngine.ordinal() + 1) % engines.length];
        progressNotifier.notify(ConversationContext.from(conversationId),
                "🔍 копаю на " + nextEngine.getName() + "...");
        return runDiscovery(DiscoverRequest.of(conversationId, lastQuery, nextEngine));
    }

    private String runDiscovery(DiscoverRequest request) {
        DiscoverResult result = discoveryAgent.handle(request);
        if (!result.releases().isEmpty()) {
            return formatReleasesForSonnet(result.releases(), result.engineUsed());
        }
        return result.summary();
    }

    private static String formatReleasesForSonnet(List<ReleaseMetadata> releases, SearchEngine engine) {
        var sb = new StringBuilder();
        String engineName = engine != null ? engine.getName() : "unknown";
        sb.append("Found ").append(releases.size()).append(" releases on ").append(engineName).append(":\n");
        for (var r : releases) {
            sb.append("- ");
            if (r.artist() != null && !r.artist().isBlank()) sb.append(r.artist()).append(" — ");
            sb.append(r.title() != null ? r.title() : "?");
            if (r.years() != null && !r.years().isEmpty()) sb.append(" (").append(r.getYearsDisplay()).append(")");
            if (r.label() != null && !r.label().isBlank()) sb.append(", ").append(r.label());
            if (r.types() != null && !r.types().isEmpty()) sb.append(" [").append(r.getTypesDisplay()).append("]");
            if (r.tags() != null && !r.tags().isEmpty()) {
                sb.append(" #").append(String.join(" #", r.tags().stream().limit(3).toList()));
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    @Tool("Download music by artist and album. Use ONLY for explicit download commands (скачай / завантаж / download).")
    public String downloadMusic(
            @P("artist name") String artist,
            @P("album / release title") String album,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "⏳ шукаю на soulseek...");
        DownloadResult result = downloadAgent.handle(DownloadRequest.byQuery(conversationId, artist, album));
        return result.summary();
    }

    @Tool("Get details about the release the user just found and answer their question — tracks, genre, year, label, music history context. Use when the user asks about the album/artist they already found, NOT to start a new search.")
    public String discussRelease(
            @P("user's question, e.g. 'які треки?', 'в якому жанрі?', 'що тоді грали'") String question,
            @ToolMemoryId String conversationId) {
        List<ReleaseMetadata> results;
        try {
            results = searchContextService.getSearchResults(conversationId);
        } catch (Exception e) {
            return "no release context — user should search first";
        }
        if (results.isEmpty()) {
            return "no release context — user should search first";
        }

        ReleaseMetadata release = results.getFirst();
        ReleaseMetadata withTracks = searchContextService.getMetadataWithTracks(release.id(), conversationId);
        if (withTracks != null) release = withTracks;

        StringBuilder sb = new StringBuilder();
        sb.append("Release: ").append(release.artist()).append(" — ").append(release.title()).append("\n");
        sb.append("Year: ").append(release.getYearsDisplay()).append("\n");
        if (!release.getTagsDisplay().isEmpty()) {
            sb.append("Genre/tags: ").append(release.getTagsDisplay()).append("\n");
        }
        if (!release.getLabelDisplay().isEmpty()) {
            sb.append("Label: ").append(release.getLabelDisplay()).append("\n");
        }

        List<TrackMetadata> tracks = release.tracks();
        if (tracks != null && !tracks.isEmpty()) {
            sb.append("Tracks:\n");
            tracks.forEach(t -> sb.append(t.number()).append(". ").append(t.title()).append("\n"));
        }

        return sb.toString().trim();
    }

    @Tool("Search the user's own music library (already downloaded and processed releases). Use when the user asks 'чи є в мене', 'є у мене', 'шукай у моїй колекції', 'в бібліотеці', 'do I have', 'in my library', or asks about a specific artist/album they may already own.")
    public String searchOwnLibrary(
            @P("search query — artist name, album title, genre tag, or any combination") String query,
            @ToolMemoryId String conversationId) {
        List<LibrarySearchResult> results = librarySearchService.search(query, 5);
        if (results.isEmpty()) {
            return "нічого не знайдено у твоїй бібліотеці за запитом: " + query;
        }
        var sb = new StringBuilder();
        sb.append("Знайдено у твоїй бібліотеці (").append(results.size()).append("):\n");
        for (LibrarySearchResult r : results) {
            sb.append("- ");
            if (r.artists() != null && !r.artists().isBlank()) sb.append(r.artists()).append(" — ");
            sb.append(r.title());
            if (r.year() != null) sb.append(" (").append(r.year()).append(")");
            if (r.tags() != null && !r.tags().isBlank()) sb.append(" [").append(r.tags()).append("]");
            sb.append(", ").append(r.trackCount()).append(" треків\n");
        }
        return sb.toString().strip();
    }

}
