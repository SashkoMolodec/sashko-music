package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.shared.model.ReleaseMetadata;
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
    private final ListenLinkResolver listenLinkResolver;

    public List<BotResponse> handleStreamingPlatforms(ConversationContext ctx, String callbackData) {
        try {
            String releaseId = callbackData.substring("STREAM:".length());
            if (releaseId.isEmpty()) {
                return List.of(BotResponse.withButtons("🤝 послухай туво", getPlatformLinksForSearch(ctx)));
            }

            ReleaseMetadata metadata = searchContextService.getReleaseMetadata(releaseId, ctx.conversationId());
            if (metadata == null) {
                log.warn("No metadata found for releaseId={} in conversation={}", releaseId, ctx.conversationId());
                return List.of(BotResponse.withButtons("🤝 послухай туво", getPlatformLinksForSearch(ctx)));
            }

            Map<String, String> platforms = buildPlatformSearchLinks(metadata.artist(), metadata.title());
            return listenLinkResolver.resolve(metadata)
                    .map(link -> List.of(BotResponse.withMultiRowButtons(
                            "▶️ " + metadata.artist() + " — " + metadata.title()
                                    + " (" + link.source() + ")\n" + link.url(),
                            List.of(List.of(BotResponse.ButtonDto.callback("▶️ слухати", "URL:" + link.url())),
                                    toButtonRow(platforms)))))
                    .orElseGet(() -> List.of(BotResponse.withButtons(
                            "прямого лінку не знайшов — ось де пошукати 👇", platforms)));
        } catch (Exception e) {
            log.error("Error getting streaming platforms: {}", e.getMessage(), e);
            return List.of(BotResponse.text("Не вдалося знайти стрімінгові платформи 😔"));
        }
    }

    private List<BotResponse.ButtonDto> toButtonRow(Map<String, String> buttons) {
        return buttons.entrySet().stream()
                .map(e -> BotResponse.ButtonDto.callback(e.getKey(), e.getValue()))
                .toList();
    }

    public Map<String, String> getPlatformLinksForSearch(ConversationContext ctx) {
        var searchRequest = searchContextService.getSearchRequest(ctx.conversationId());
        return buildPlatformSearchLinks(searchRequest.artist(), searchRequest.getTitle());
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
