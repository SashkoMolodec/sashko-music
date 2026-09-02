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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkersFlowService {

    private static final String FLOW_KEY = "markers_creating";
    private static final String REMOVE_FLOW_KEY = "markers_removing";

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

        var row = List.of(
                BotResponse.ButtonDto.callback("➕", "MARKERS_ADD"),
                BotResponse.ButtonDto.callback("🗑", "MARKERS_RM")
        );
        return List.of(BotResponse.withMultiRowButtons(sb.toString().stripTrailing(), List.of(row)));
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

    @Transactional
    public List<BotResponse> promptRemove(ConversationContext ctx) {
        List<Marker> markers = markerRepository.findAll();
        if (markers.isEmpty()) {
            return List.of(BotResponse.text("міток ще немає"));
        }
        List<Long> ids = markers.stream().map(Marker::getId).toList();
        chatStateStore.put(ctx.conversationId(), REMOVE_FLOW_KEY, new RemovalContext(ids));
        return List.of(BotResponse.text("🤔 введи номер(и) міток для видалення через кому (напр. 1,3)"));
    }

    public boolean isWaitingForRemoval(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), REMOVE_FLOW_KEY, RemovalContext.class).isPresent();
    }

    @Transactional
    public List<BotResponse> removeMarkers(ConversationContext ctx, String input) {
        var removalCtx = chatStateStore.get(ctx.conversationId(), REMOVE_FLOW_KEY, RemovalContext.class);
        if (removalCtx.isEmpty()) {
            return List.of(BotResponse.text("😔 сесія протухла — тисни 🗑 знову"));
        }
        chatStateStore.remove(ctx.conversationId(), REMOVE_FLOW_KEY);
        List<Long> ids = removalCtx.get().markerIds();

        List<Integer> numbers;
        try {
            numbers = Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("не зрозумів номери — введи через кому, напр. 1,3"));
        }
        if (numbers.isEmpty()) {
            return List.of(BotResponse.text("вкажи хоч один номер мітки"));
        }
        for (int n : numbers) {
            if (n < 1 || n > ids.size()) {
                return List.of(BotResponse.text("номер %d поза межами (усього %d міток)".formatted(n, ids.size())));
            }
        }

        List<String> removedNames = new ArrayList<>();
        for (int n : new LinkedHashSet<>(numbers)) {
            Long markerId = ids.get(n - 1);
            markerRepository.findById(markerId).ifPresent(marker -> {
                removedNames.add(marker.getName());
                markerRepository.deleteById(markerId);
            });
        }

        return List.of(BotResponse.text("🗑 видалено: " + String.join(", ", removedNames)));
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        chatStateStore.remove(event.conversationId(), FLOW_KEY);
        chatStateStore.remove(event.conversationId(), REMOVE_FLOW_KEY);
    }

    private record RemovalContext(List<Long> markerIds) {}
}
