package com.sashkomusic.mainagent.library;

import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.messaging.RemoveReleaseTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RemoveReleaseFlowService {

    private static final String CB_CONFIRM = "RM_OK:";
    private static final String CB_CANCEL = "RM_NO:";

    private final LibrarySearchService librarySearchService;
    private final ReleaseRepository releaseRepository;
    private final RemoveReleaseTaskProducer taskProducer;

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
        release.getTracks().stream()
                .sorted(Comparator.comparing(t -> t.getTrackNumber() == null ? 0 : t.getTrackNumber()))
                .forEach(t -> sb.append("  ")
                        .append(t.getTrackNumber() == null ? "•" : t.getTrackNumber() + ".")
                        .append(" ").append(escape(t.getTitle())).append("\n"));

        sb.append("\n♻️ папку буде перенесено у trash, метадані з бази видаляться");

        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("✅", CB_CONFIRM + release.getId());
        buttons.put("❌", CB_CANCEL + release.getId());

        return List.of(BotResponse.htmlWithButtons(sb.toString(), buttons));
    }

    private Long parseId(String callbackData, String prefix) {
        try {
            return Long.parseLong(callbackData.substring(prefix.length()));
        } catch (NumberFormatException ex) {
            log.warn("Failed to parse release id from callback: {}", callbackData);
            return null;
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
