package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.libraryagent.domain.entity.Marker;
import com.sashkomusic.libraryagent.domain.repository.MarkerRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkersFlowService {

    private static final String FLOW_KEY = "markers_creating";

    private final MarkerRepository markerRepository;
    private final ChatStateStore chatStateStore;

    @Transactional(readOnly = true)
    public List<BotResponse> showMarkers(ConversationContext ctx) {
        List<Marker> markers = markerRepository.findAll();

        StringBuilder sb = new StringBuilder("🏷 мітки");
        if (markers.isEmpty()) {
            sb.append("\n\nпоки немає жодної.");
        } else {
            sb.append("\n\n");
            for (int i = 0; i < markers.size(); i++) {
                sb.append(i + 1).append(". ").append(markers.get(i).getName().toLowerCase()).append("\n");
            }
        }

        var addBtn = List.of(BotResponse.ButtonDto.callback("➕", "MARKERS_ADD"));
        return List.of(BotResponse.withMultiRowButtons(sb.toString().stripTrailing(), List.of(addBtn)));
    }

    public List<BotResponse> promptCreate(ConversationContext ctx) {
        chatStateStore.put(ctx.conversationId(), FLOW_KEY, true);
        return List.of(BotResponse.text("введи назву нової мітки:"));
    }

    public boolean isWaitingForName(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), FLOW_KEY, Boolean.class)
                .orElse(false);
    }

    @Transactional
    public List<BotResponse> createMarker(ConversationContext ctx, String name) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY);
        String trimmed = name.trim().toLowerCase();
        if (trimmed.isBlank()) {
            return List.of(BotResponse.text("назва не може бути порожньою."));
        }
        if (markerRepository.existsByName(trimmed)) {
            return List.of(BotResponse.text("мітка «" + trimmed + "» вже існує."));
        }
        markerRepository.save(new Marker(trimmed));
        return List.of(BotResponse.text("✅ мітка «" + trimmed + "» створена"));
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        chatStateStore.remove(event.conversationId(), FLOW_KEY);
    }
}
