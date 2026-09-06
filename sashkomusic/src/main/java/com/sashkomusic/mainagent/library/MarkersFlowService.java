package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.libraryagent.domain.entity.Marker;
import com.sashkomusic.libraryagent.domain.repository.MarkerRepository;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDsl;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistService;
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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarkersFlowService {

    private static final String FLOW_KEY = "markers_creating";
    private static final String REMOVE_FLOW_KEY = "markers_removing";

    private final MarkerRepository markerRepository;
    private final ChatStateStore chatStateStore;
    private final SmartlistService smartlistService;

    @Transactional(readOnly = true)
    public List<BotResponse> showMarkers(ConversationContext ctx) {
        List<Marker> markers = markerRepository.findAllByOrderByLastUsedAtDescNameAsc();

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
        return List.of(BotResponse.withButtons("введи назву нової мітки:", Map.of("❌", "MARKERS_ADD_CANCEL")));
    }

    public boolean isWaitingForName(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), FLOW_KEY, Boolean.class)
                .orElse(false);
    }

    public List<BotResponse> cancelCreate(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY);
        return List.of(BotResponse.text("❌ скасовано"));
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
        return List.of(BotResponse.text("✅ мітка «" + trimmed + "» створена" + createMatchingSmartlist(trimmed)));
    }

    /** Every marker gets a matching smartlist: tagged with it and rated above 1 star. */
    private String createMatchingSmartlist(String markerName) {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("comment", "(" + markerName + ")"),
                new SmartlistDsl.GtCondition("rating", 1)
        ));
        try {
            smartlistService.create(markerName, dsl);
            return "\n🧠 і смартлист «" + markerName + "»";
        } catch (Exception e) {
            log.warn("Failed to auto-create smartlist for marker '{}': {}", markerName, e.getMessage());
            return "\n⚠️ смартлист не створився: " + e.getMessage();
        }
    }

    @Transactional
    public List<BotResponse> promptRemove(ConversationContext ctx) {
        List<Marker> markers = markerRepository.findAll();
        if (markers.isEmpty()) {
            return List.of(BotResponse.text("міток ще немає"));
        }
        List<Long> ids = markers.stream().map(Marker::getId).toList();
        chatStateStore.put(ctx.conversationId(), REMOVE_FLOW_KEY, new RemovalContext(ids));
        return List.of(BotResponse.withButtons(
                "🤔 введи номер(и) міток для видалення через кому (напр. 1,3)",
                Map.of("❌", "MARKERS_RM_CANCEL")));
    }

    public boolean isWaitingForRemoval(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), REMOVE_FLOW_KEY, RemovalContext.class).isPresent();
    }

    public List<BotResponse> cancelRemove(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), REMOVE_FLOW_KEY);
        return List.of(BotResponse.text("❌ скасовано"));
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
