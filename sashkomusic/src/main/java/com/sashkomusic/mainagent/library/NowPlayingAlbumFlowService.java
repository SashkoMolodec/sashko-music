package com.sashkomusic.mainagent.library;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackTag;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackTagRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import com.sashkomusic.mainagent.library.messaging.AddCommentTaskProducer;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NowPlayingAlbumFlowService {

    private static final String LOCAL_FILE_PREFIX = "LOCAL_FILE:";

    private final NavidromeClient navidromeClient;
    private final TrackService trackService;
    private final TrackRepository trackRepository;
    private final TrackTagRepository trackTagRepository;
    private final ReleaseRepository releaseRepository;
    private final RemoveReleaseFlowService removeReleaseFlowService;
    private final AddCommentTaskProducer addCommentTaskProducer;
    private final AlbumCommentContextHolder commentContextHolder;

    @Transactional(readOnly = true)
    public List<BotResponse> nowPlayingAlbum(ConversationContext ctx) {
        NavidromeClient.CurrentTrackInfo trackInfo = navidromeClient.getCurrentlyPlayingTrackInfo();
        if (trackInfo == null) {
            return List.of(BotResponse.text("зараз нич не грає 🥺"));
        }

        Optional<TrackDto> trackDtoOpt = trackService.findByArtistAndTitleOptional(trackInfo.artist(), trackInfo.title());
        if (trackDtoOpt.isEmpty()) {
            return List.of(BotResponse.text("зараз грає: %s – %s, але трек не знайдено в БД"
                    .formatted(trackInfo.artist(), trackInfo.title())));
        }

        TrackDto trackDto = trackDtoOpt.get();
        Optional<Track> trackOpt = trackRepository.findById(trackDto.id());
        if (trackOpt.isEmpty() || trackOpt.get().getRelease() == null) {
            return List.of(BotResponse.text("реліз для цього треку не знайдено."));
        }

        Release release = trackOpt.get().getRelease();
        List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(release.getId());

        String cardText = buildMainCardText(release, tracks);
        List<List<BotResponse.ButtonDto>> rows = List.of(List.of(
                BotResponse.ButtonDto.callback("ℹ️", "ALB_INFO:" + release.getId()),
                BotResponse.ButtonDto.callback("💬", "ALB_COMMENT:" + release.getId()),
                BotResponse.ButtonDto.callback("🗑", "ALB_RM:" + release.getId())
        ));

        String imageUrl = release.getCoverPath() != null ? LOCAL_FILE_PREFIX + release.getCoverPath() : null;
        return List.of(BotResponse.cardWithRows(cardText, imageUrl, rows));
    }

    @Transactional(readOnly = true)
    public List<BotResponse> handleInfo(ConversationContext ctx, Long releaseId) {
        Optional<Release> releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("реліз не знайдено."));
        }
        Release release = releaseOpt.get();
        List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(releaseId);

        List<Long> trackIds = tracks.stream().map(Track::getId).toList();
        Map<Long, Map<String, String>> tagsByTrackId = loadTagsMap(trackIds);

        return List.of(BotResponse.text(buildInfoText(release, tracks, tagsByTrackId)));
    }

    public List<BotResponse> handleComment(ConversationContext ctx, Long releaseId) {
        commentContextHolder.set(ctx.conversationId(), releaseId);
        var labelBtn = List.of(BotResponse.ButtonDto.callback("🏷", "LBL_LIST:A:" + releaseId));
        return List.of(BotResponse.withMultiRowButtons("введи комент (додасться до всіх треків альбому):", List.of(labelBtn)));
    }

    @Transactional(readOnly = true)
    public List<BotResponse> applyComment(ConversationContext ctx, String comment) {
        Optional<Long> releaseIdOpt = commentContextHolder.get(ctx.conversationId());
        if (releaseIdOpt.isEmpty()) {
            return List.of(BotResponse.text("немає активного контексту альбому."));
        }
        Long releaseId = releaseIdOpt.get();
        commentContextHolder.clear(ctx.conversationId());

        List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(releaseId);
        for (Track track : tracks) {
            addCommentTaskProducer.send(new AddCommentTaskDto(track.getId(), comment, ctx.conversationId()));
        }
        return List.of(BotResponse.text("✅ комент «%s» додається до %d треків".formatted(comment, tracks.size())));
    }

    public List<BotResponse> handleDelete(ConversationContext ctx, Long releaseId) {
        return removeReleaseFlowService.presentConfirmationByReleaseId(releaseId);
    }

    private String buildMainCardText(Release release, List<Track> tracks) {
        String artists = release.getArtists().stream()
                .map(Artist::getName)
                .collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("💿 ").append(release.getTitle());
        if (release.getInitialRelease() != null) {
            sb.append("  (").append(release.getInitialRelease()).append(")");
        }
        sb.append("\n👤 ").append(artists.isEmpty() ? "?" : artists).append("\n\n");

        for (Track t : tracks) {
            int num = t.getTrackNumber() != null ? t.getTrackNumber() : 0;
            sb.append(num > 0 ? num + ". " : "• ").append(t.getTitle());
            if (t.getDuration() != null) {
                sb.append("  ").append(formatDuration(t.getDuration()));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing().toLowerCase();
    }

    private String buildInfoText(Release release, List<Track> tracks, Map<Long, Map<String, String>> tagsByTrackId) {
        String format = tracks.isEmpty() ? "" : getFormat(tracks.get(0).getLocalPath());
        String sublibraryEmoji = "vault".equals(release.getSublibrary()) ? "📦" : "🎵";

        StringBuilder sb = new StringBuilder();
        sb.append(sublibraryEmoji).append(" ").append(release.getSublibrary());
        if (!format.isEmpty()) {
            sb.append("  ·  ").append(format);
        }

        for (Track t : tracks) {
            Map<String, String> tags = tagsByTrackId.getOrDefault(t.getId(), Map.of());
            int num = t.getTrackNumber() != null ? t.getTrackNumber() : 0;

            sb.append("\n\n");
            sb.append(num > 0 ? num + ". " : "• ").append(t.getTitle());
            if (t.getDuration() != null) {
                sb.append("  (").append(formatDuration(t.getDuration())).append(")");
            }

            String stars = toStars(tags.get("RATING"));
            String functionEmoji = toFunctionEmoji(tags.get("DJ_FUNCTION"));
            String comment = tags.get("COMM");

            sb.append("\n   ").append(stars);
            if (!functionEmoji.isEmpty()) {
                sb.append("  ").append(functionEmoji);
            }
            if (comment != null && !comment.isBlank()) {
                sb.append("  💬 ").append(comment);
            }
        }
        return sb.toString().stripTrailing().toLowerCase();
    }

    private Map<Long, Map<String, String>> loadTagsMap(List<Long> trackIds) {
        if (trackIds.isEmpty()) return Map.of();
        List<TrackTag> allTags = trackTagRepository.findAllByTrackIds(trackIds);
        return allTags.stream().collect(Collectors.groupingBy(
                tt -> tt.getTrack().getId(),
                Collectors.toMap(TrackTag::getTagName, TrackTag::getTagValue, (v1, v2) -> v1)
        ));
    }

    private String toStars(String rating) {
        if (rating == null) return "☆☆☆☆☆";
        try {
            int r = Integer.parseInt(rating);
            if (r == 0) return "☆☆☆☆☆";
            int stars = r <= 51 ? 1 : r <= 102 ? 2 : r <= 153 ? 3 : r <= 204 ? 4 : 5;
            return "★".repeat(stars) + "☆".repeat(5 - stars);
        } catch (NumberFormatException e) {
            return "☆☆☆☆☆";
        }
    }

    private String toFunctionEmoji(String function) {
        if (function == null) return "";
        return switch (function) {
            case "intro" -> "🌅";
            case "tool" -> "🔧";
            case "banger" -> "💥";
            case "closer" -> "🎆";
            default -> "";
        };
    }

    private String formatDuration(int seconds) {
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private String getFormat(String localPath) {
        if (localPath == null) return "";
        int dot = localPath.lastIndexOf('.');
        if (dot < 0 || dot >= localPath.length() - 1) return "";
        return localPath.substring(dot + 1).toUpperCase();
    }
}
