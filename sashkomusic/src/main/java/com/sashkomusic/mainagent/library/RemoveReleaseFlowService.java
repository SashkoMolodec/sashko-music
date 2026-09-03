package com.sashkomusic.mainagent.library;

import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackTag;
import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackTagRepository;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.libraryagent.domain.service.TrackRemovalService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.messaging.RemoveReleaseTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RemoveReleaseFlowService {

    private static final String CB_CONFIRM = "RM_OK:";
    private static final String CB_SELECT = "RM_SEL:";
    private static final String CB_SELECT_CANCEL = "RM_SEL_CANCEL";
    private static final String CB_CANCEL = "RM_NO:";

    private final LibrarySearchService librarySearchService;
    private final ReleaseRepository releaseRepository;
    private final RemoveReleaseTaskProducer taskProducer;
    private final TrackRemovalService trackRemovalService;
    private final TrackRemovalContextHolder trackRemovalContextHolder;
    private final TrackTagRepository trackTagRepository;

    @Transactional(readOnly = true)
    public List<BotResponse> presentConfirmationByQuery(String query) {
        if (query == null || query.isBlank()) {
            return List.of(BotResponse.text("❌ не зрозумів який реліз видаляти"));
        }
        List<LibrarySearchResult> results = librarySearchService.search(query, 1);
        if (results.isEmpty()) {
            return List.of(BotResponse.text("❌ нічого не знайшов по: " + query));
        }
        return buildConfirmationCard(results.get(0));
    }

    @Transactional(readOnly = true)
    public List<BotResponse> presentConfirmationByReleaseId(Long releaseId) {
        var releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ реліз не знайдено в базі"));
        }
        Release release = releaseOpt.get();
        String artists = release.getArtists().isEmpty()
                ? "?"
                : release.getArtists().iterator().next().getName();
        LibrarySearchResult synthetic = new LibrarySearchResult(
                release.getId(),
                release.getTitle(),
                artists,
                release.getInitialRelease(),
                null,
                release.getDirectoryPath(),
                release.getTracks().size(),
                0.0
        );
        return buildConfirmationCard(synthetic);
    }

    @Transactional(readOnly = true)
    public List<BotResponse> handleConfirm(ConversationContext ctx, String callbackData) {
        Long releaseId = parseId(callbackData, CB_CONFIRM);
        if (releaseId == null) {
            return List.of(BotResponse.text("❌ не зрозумів який реліз"));
        }

        var releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ реліз вже не існує в базі"));
        }

        taskProducer.send(ctx.conversationId(), releaseId);
        return List.of(BotResponse.text("🗑️ переношу у trash: " + releaseOpt.get().getTitle() + "..."));
    }

    public List<BotResponse> handleCancel(ConversationContext ctx, String callbackData) {
        return List.of(BotResponse.text("✅ скасовано, нічого не видалено"));
    }

    @Transactional
    public List<BotResponse> promptTrackSelection(ConversationContext ctx, String callbackData) {
        Long releaseId = parseId(callbackData, CB_SELECT);
        if (releaseId == null) {
            return List.of(BotResponse.text("❌ не зрозумів який реліз"));
        }
        var releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ реліз вже не існує в базі"));
        }
        trackRemovalContextHolder.markSelecting(ctx.conversationId(), releaseId);

        StringBuilder sb = new StringBuilder("🤔 введи номери треків для видалення через кому (напр. 1,2,5):\n\n");
        appendTrackListSortedByRating(sb, releaseOpt.get().getTracks(), "");

        return List.of(BotResponse.withButtons(sb.toString().stripTrailing(), Map.of("❌", CB_SELECT_CANCEL)));
    }

    public boolean isSelectingTracks(ConversationContext ctx) {
        return trackRemovalContextHolder.get(ctx.conversationId()).isPresent();
    }

    public List<BotResponse> cancelTrackSelection(ConversationContext ctx) {
        trackRemovalContextHolder.clear(ctx.conversationId());
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> handleTrackSelection(ConversationContext ctx, String input) {
        var pending = trackRemovalContextHolder.get(ctx.conversationId());
        if (pending.isEmpty()) {
            return List.of(BotResponse.text("😔 сесія протухла — спробуй ще раз"));
        }
        trackRemovalContextHolder.clear(ctx.conversationId());
        Long releaseId = pending.get().releaseId();

        List<Integer> numbers;
        try {
            numbers = Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("не зрозумів номери — введи через кому, напр. 1,2,5"));
        }
        if (numbers.isEmpty()) {
            return List.of(BotResponse.text("вкажи хоч один номер треку"));
        }

        TrackRemovalService.TrackRemovalResult result = trackRemovalService.removeTracks(releaseId, numbers);
        if (!result.success()) {
            return List.of(BotResponse.text("❌ " + result.message()));
        }

        StringBuilder sb = new StringBuilder("🗑 видалено:\n");
        result.removedTitles().forEach(t -> sb.append("  ").append(t).append("\n"));
        if (!result.notFoundNumbers().isEmpty()) {
            sb.append("\n⚠️ не знайдено номер(и): ")
                    .append(result.notFoundNumbers().stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (result.message() != null) {
            sb.append("\n\n").append(result.message());
        }
        return List.of(BotResponse.text(sb.toString().stripTrailing()));
    }

    private List<BotResponse> buildConfirmationCard(LibrarySearchResult match) {
        var releaseOpt = releaseRepository.findById(match.releaseId());
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ реліз зник з бази"));
        }
        Release release = releaseOpt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("🗑️ підтверди видалення\n\n");
        sb.append(escape(match.artists() != null ? match.artists() : "?"))
                .append(" — ").append(escape(release.getTitle())).append("\n");
        if (match.year() != null) {
            sb.append("📅 ").append(match.year()).append("\n");
        }
        if (match.tags() != null && !match.tags().isBlank()) {
            sb.append("🏷️ ").append(escape(match.tags())).append("\n");
        }
        sb.append("📁 <code>").append(escape(release.getDirectoryPath())).append("</code>\n\n");

        sb.append("треки (").append(release.getTracks().size()).append("):\n");
        appendTrackListSortedByRating(sb, release.getTracks(), "  ");

        sb.append("\n♻️ папку буде перенесено у trash, метадані з бази видаляться");

        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("✅", CB_CONFIRM + release.getId());
        buttons.put("🔢", CB_SELECT + release.getId());
        buttons.put("❌", CB_CANCEL + release.getId());

        return List.of(BotResponse.htmlWithButtons(sb.toString(), buttons));
    }

    private void appendTrackListSortedByRating(StringBuilder sb, Collection<Track> trackCollection, String indent) {
        List<Track> tracks = new ArrayList<>(trackCollection);
        Map<Long, String> ratingByTrackId = trackTagRepository.findAllByTrackIds(tracks.stream().map(Track::getId).toList())
                .stream()
                .filter(tt -> "RATING".equals(tt.getTagName()))
                .collect(Collectors.toMap(tt -> tt.getTrack().getId(), TrackTag::getTagValue, (v1, v2) -> v1));

        tracks.stream()
                .sorted(Comparator.comparingInt(t -> ratingWmp(ratingByTrackId.get(t.getId()))))
                .forEach(t -> {
                    sb.append(indent)
                            .append(t.getTrackNumber() == null ? "•" : t.getTrackNumber() + ".")
                            .append(" ").append(escape(t.getTitle()));
                    String stars = toStars(ratingByTrackId.get(t.getId()));
                    if (!stars.isEmpty()) {
                        sb.append("  ").append(stars);
                    }
                    sb.append("\n");
                });
    }

    private Long parseId(String callbackData, String prefix) {
        try {
            return Long.parseLong(callbackData.substring(prefix.length()));
        } catch (NumberFormatException ex) {
            log.warn("Failed to parse release id from callback: {}", callbackData);
            return null;
        }
    }

    private int ratingWmp(String rating) {
        if (rating == null) return 0;
        try {
            return Integer.parseInt(rating);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String toStars(String rating) {
        int r = ratingWmp(rating);
        if (r == 0) return "";
        int stars = r <= 51 ? 1 : r <= 102 ? 2 : r <= 153 ? 3 : r <= 204 ? 4 : 5;
        return "⭐".repeat(stars);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
