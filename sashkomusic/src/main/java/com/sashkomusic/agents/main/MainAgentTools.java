package com.sashkomusic.agents.main;

import com.sashkomusic.agents.bridge.ProgressNotifier;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.DiscoverResult;
import com.sashkomusic.agents.contract.DownloadRequest;
import com.sashkomusic.agents.contract.DownloadResult;
import com.sashkomusic.agents.contract.LibraryRequest;
import com.sashkomusic.agents.contract.LibraryResult;
import com.sashkomusic.agents.discovery.DiscoveryAgentService;
import com.sashkomusic.agents.download.DownloadAgentService;
import com.sashkomusic.agents.library.LibraryAgentService;
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
    private final LibraryAgentService libraryAgent;
    private final ProgressNotifier progressNotifier;
    private final SearchContextService searchContextService;

    @Tool("Search for music — artist, album or track. Use for free-form discovery requests.")
    public String findMusic(
            @P("user's full query, e.g. 'Burial new album', 'Aphex Twin discogs', 'копай'") String query,
            @ToolMemoryId long chatId) {
        progressNotifier.notify(chatId, "🔍 шукаю...");
        DiscoverResult result = discoveryAgent.handle(DiscoverRequest.of(chatId, query));
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
            @ToolMemoryId long chatId) {
        progressNotifier.notify(chatId, "⏳ шукаю на soulseek...");
        DownloadResult result = downloadAgent.handle(DownloadRequest.byQuery(chatId, artist, album));
        return result.summary();
    }

    @Tool("Get details about the release the user just found and answer their question — tracks, genre, year, label, music history context. Use when the user asks about the album/artist they already found, NOT to start a new search.")
    public String discussRelease(
            @P("user's question, e.g. 'які треки?', 'в якому жанрі?', 'що тоді грали'") String question,
            @ToolMemoryId long chatId) {
        List<ReleaseMetadata> results;
        try {
            results = searchContextService.getSearchResults(chatId);
        } catch (Exception e) {
            return "no release context — user should search first";
        }
        if (results.isEmpty()) {
            return "no release context — user should search first";
        }

        ReleaseMetadata release = results.getFirst();
        ReleaseMetadata withTracks = searchContextService.getMetadataWithTracks(release.id(), chatId);
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

    @Tool("Apply a library operation to the currently-playing track: rate, set energy, set function, or add comment.")
    public String manageLibrary(
            @P("user's natural-language command, e.g. 'rate 5', 'energy 3', 'мракнути банжер', 'коментар крутий'") String command,
            @ToolMemoryId long chatId) {
        LibraryResult result = libraryAgent.handle(LibraryRequest.of(chatId, command));
        return result.summary();
    }
}
