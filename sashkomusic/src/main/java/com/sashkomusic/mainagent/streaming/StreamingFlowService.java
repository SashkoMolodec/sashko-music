package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingFlowService {

    private static final String FLOW_KEY = "listen_msg";

    private final SearchContextService searchContextService;
    private final ListenLinkResolver listenLinkResolver;
    private final ChatStateStore chatStateStore;

    public List<BotResponse> handleStreamingPlatforms(ConversationContext ctx, String callbackData) {
        try {
            String releaseId = callbackData.substring("STREAM:".length());
            if (releaseId.isEmpty()) {
                return List.of(BotResponse.text("нема релізу в контексті — спочатку пошукай щось."));
            }

            ReleaseMetadata metadata = searchContextService.getReleaseMetadata(releaseId, ctx.conversationId());
            if (metadata == null) {
                log.warn("No metadata found for releaseId={} in conversation={}", releaseId, ctx.conversationId());
                return List.of(BotResponse.text("реліз загубився з контексту — спробуй пошукати ще раз."));
            }

            String text = listenLinkResolver.resolve(metadata)
                    .map(result -> buildListenText(ctx.conversationId(), releaseId, metadata, result))
                    .orElse("не знайшов де це послухати 😔");
            return List.of(reuseListenMessage(ctx, text));
        } catch (Exception e) {
            log.error("Error building listen link: {}", e.getMessage(), e);
            return List.of(BotResponse.text("Не вдалося знайти де послухати 😔"));
        }
    }

    private String buildListenText(String conversationId, String releaseId,
                                   ReleaseMetadata metadata, ListenLinkResolver.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[▶️ ").append(metadata.artist()).append(" — ").append(metadata.title())
                .append("](").append(result.primary().url()).append(")")
                .append(" · ").append(result.source());

        listenLinkResolver.appleMusic(metadata).ifPresent(apple -> sb.append("\n[🍏 ")
                .append(linkLabel(apple)).append("](").append(apple.url()).append(")"));

        String tracklist = buildTracklistText(conversationId, releaseId);
        if (!tracklist.isBlank()) {
            sb.append("\n\n").append(tracklist);
        }

        List<ListenLinkResolver.ListenLink> alternates = result.alternates();
        if (!alternates.isEmpty()) {
            sb.append("\n\n🎬 ще з ").append(result.source()).append(":");
            for (var link : alternates) {
                sb.append("\n[▶️ ").append(linkLabel(link)).append("](").append(link.url()).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * Clicking 🎧 across a run of cards used to leave a trail of long listen messages. One slot per
     * conversation gets reused instead, so the latest release always answers in the same message.
     */
    private BotResponse reuseListenMessage(ConversationContext ctx, String text) {
        Integer previous = chatStateStore.get(ctx.conversationId(), FLOW_KEY, Integer.class).orElse(null);
        return BotResponse.reusingMessage(previous, FLOW_KEY, text);
    }

    // Markdown link labels break on an unescaped ']', and a blank label renders as a dead link.
    private String linkLabel(ListenLinkResolver.ListenLink link) {
        String title = link.title();
        if (title == null || title.isBlank()) return "лінк";
        return title.replace("[", "(").replace("]", ")");
    }

    private String buildTracklistText(String conversationId, String releaseId) {
        try {
            var metadata = searchContextService.getMetadataWithTracks(releaseId, conversationId);
            if (metadata == null || metadata.tracks() == null || metadata.tracks().isEmpty()) return "";

            var sb = new StringBuilder();
            for (var track : metadata.tracks()) {
                sb.append(track.number()).append(". ").append(track.title().toLowerCase()).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.warn("Could not fetch tracklist for releaseId={}: {}", releaseId, e.getMessage());
            return "";
        }
    }
}
