package com.sashkomusic.mainagent.library;

import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.MoveReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SublibraryAssignmentHandler {

    private final ApplicationEventPublisher eventPublisher;
    private final ReleaseRepository releaseRepository;

    @Transactional(readOnly = true)
    public List<BotResponse> handle(ConversationContext ctx, String data) {
        // data format: "LIB_ASSIGN:<releaseId>:<sublibrary>"
        String payload = data.substring("LIB_ASSIGN:".length());
        int sep = payload.indexOf(':');
        if (sep <= 0) {
            return List.of(BotResponse.text("❌ некоректний callback"));
        }
        Long releaseId;
        try {
            releaseId = Long.parseLong(payload.substring(0, sep));
        } catch (NumberFormatException ex) {
            return List.of(BotResponse.text("❌ некоректний releaseId"));
        }
        String sublibrary = payload.substring(sep + 1);

        Optional<Release> releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ реліз не знайдено"));
        }
        Release release = releaseOpt.get();
        if (sublibrary.equals(release.getSublibrary())) {
            return List.of(BotResponse.text("✅ залишаємо у " + sublibrary));
        }

        eventPublisher.publishEvent(new MoveReleaseTaskEvent(ctx.conversationId(), releaseId, sublibrary));
        return List.of(BotResponse.text("🚚 переношу '" + release.getTitle() + "' у " + sublibrary + "..."));
    }
}
