package com.sashkomusic.mainagent.streaming;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
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

    private final SearchContextService searchContextService;
    private final ListenLinkResolver listenLinkResolver;

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

            return listenLinkResolver.resolve(metadata)
                    .map(result -> List.of(BotResponse.text(
                            buildListenText(ctx.conversationId(), releaseId, metadata, result))))
                    .orElseGet(() -> List.of(BotResponse.text("не знайшов де це послухати 😔")));
        } catch (Exception e) {
            log.error("Error building listen link: {}", e.getMessage(), e);
            return List.of(BotResponse.text("Не вдалося знайти де послухати 😔"));
        }
    }

    private String buildListenText(String conversationId, String releaseId,
                                   ReleaseMetadata metadata, ListenLinkResolver.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("▶️ ").append(metadata.artist()).append(" — ").append(metadata.title())
                .append(" (").append(result.source()).append(")\n")
                .append(result.primary().url());

        String tracklist = buildTracklistText(conversationId, releaseId);
        if (!tracklist.isBlank()) {
            sb.append("\n\n").append(tracklist);
        }

        List<ListenLinkResolver.ListenLink> alternates = result.alternates();
        if (!alternates.isEmpty()) {
            sb.append("\n\n🎬 ще з ").append(result.source()).append(":");
            for (var link : alternates) {
                sb.append("\n");
                if (link.title() != null && !link.title().isBlank()) {
                    sb.append(link.title()).append("\n");
                }
                sb.append(link.url());
            }
        }
        return sb.toString();
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
