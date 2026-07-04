package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.shared.util.SearchUrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingFlowService {

    private final SearchContextService searchContextService;

    public List<BotResponse> handleStreamingPlatforms(ConversationContext ctx, String callbackData) {
        try {
            var platforms = handleStreamingCallback(ctx, callbackData);
            String releaseId = callbackData.substring("STREAM:".length());
            List<BotResponse> responses = new java.util.ArrayList<>();

            if (!releaseId.isEmpty()) {
                String tracklist = buildTracklistText(ctx.conversationId(), releaseId);
                if (!tracklist.isBlank()) {
                    responses.add(BotResponse.text(tracklist));
                }
            }

            responses.add(BotResponse.withButtons("🤝 послухай туво", platforms));
            return responses;
        } catch (Exception e) {
            log.error("Error getting streaming platforms: {}", e.getMessage(), e);
            return List.of(BotResponse.text("Не вдалося знайти стрімінгові платформи 😔"));
        }
    }

    private String buildTracklistText(String conversationId, String releaseId) {
        try {
            var metadata = searchContextService.getMetadataWithTracks(releaseId, conversationId);
            if (metadata == null || metadata.tracks() == null || metadata.tracks().isEmpty()) return "";

            var sb = new StringBuilder();
            sb.append("_").append(metadata.artist()).append(" — ").append(metadata.title()).append("_\n");
            for (var track : metadata.tracks()) {
                sb.append(track.number()).append(". ").append(track.title().toLowerCase()).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("Could not fetch tracklist for releaseId={}: {}", releaseId, e.getMessage());
            return "";
        }
    }

    public Map<String, String> handleStreamingCallback(ConversationContext ctx, String callbackData) {
        log.info("Handling streaming platforms request, callback={}", callbackData);

        String releaseId = callbackData.substring("STREAM:".length());
        return releaseId.isEmpty()
                ? getPlatformLinksForSearch(ctx)
                : getPlatformLinks(ctx.conversationId(), releaseId);
    }

    public Map<String, String> getPlatformLinksForSearch(ConversationContext ctx) {
        var searchRequest = searchContextService.getSearchRequest(ctx.conversationId());
        return buildPlatformSearchLinks(searchRequest.artist(), searchRequest.getTitle());
    }

    public Map<String, String> getPlatformLinks(String conversationId, String releaseId) {
        ReleaseMetadata metadata = searchContextService.getReleaseMetadata(releaseId, conversationId);
        if (metadata == null) {
            log.warn("No metadata found for releaseId={} in conversation={}", releaseId, conversationId);
            return Map.of("▶️", "URL:https://youtube.com");
        }
        return buildPlatformSearchLinks(metadata.artist(), metadata.title());
    }

    private Map<String, String> buildPlatformSearchLinks(String artist, String title) {
        Map<String, String> buttons = new LinkedHashMap<>();
        String query = artist + " " + title;

        buttons.put("🟢", "URL:https://open.spotify.com/search/" + SearchUrlUtils.encode(query));

        String appleUrl = "https://music.apple.com/ua/search?l=uk&term="
                + SearchUrlUtils.encode(query.toLowerCase()).replace("+", "%20");
        buttons.put("🍏", "URL:" + appleUrl);

        buttons.put("🧡", "URL:https://soundcloud.com/search?q=" + SearchUrlUtils.encode(query));
        buttons.put("📼", "URL:https://bandcamp.com/search?q=" + SearchUrlUtils.encode(query));
        buttons.put("🎵", "URL:https://music.youtube.com/search?q=" + SearchUrlUtils.encode(query));
        buttons.put("▶️", buildYoutubeSearchUrl(artist, title));

        return buttons;
    }

    private String buildYoutubeSearchUrl(String artist, String title) {
        String albumWord = SearchUrlUtils.buildYoutubeAlbumWord(
                SearchUrlUtils.detectLanguage(artist, title));
        String query = artist + " " + title + " " + albumWord;
        return "URL:https://www.youtube.com/results?search_query=" + SearchUrlUtils.encode(query);
    }
}
