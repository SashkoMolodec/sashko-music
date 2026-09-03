package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartlistsFlowService {

    private static final String FLOW_KEY_CREATE = "smartlists_creating";
    private static final String FLOW_KEY_REMOVE = "smartlists_removing";
    private static final String FLOW_KEY_INFO = "smartlists_info";

    private final SmartlistService smartlistService;
    private final SmartlistCreationFlowService smartlistCreationFlowService;
    private final ChatStateStore chatStateStore;

    public List<BotResponse> showList(ConversationContext ctx) {
        List<SmartlistService.SmartlistSummary> all = smartlistService.list();

        StringBuilder sb = new StringBuilder("🧠 смартлисти");
        if (all.isEmpty()) {
            sb.append("\n\nпоки немає жодного.");
        } else {
            sb.append("\n\n");
            for (int i = 0; i < all.size(); i++) {
                var s = all.get(i);
                sb.append(i + 1).append(". ").append(s.name().toLowerCase())
                        .append("  ·  ").append(s.trackCount()).append(" треків\n");
            }
        }

        var row = List.of(
                BotResponse.ButtonDto.callback("➕", "SMARTLISTS_ADD"),
                BotResponse.ButtonDto.callback("🗑", "SMARTLISTS_RM"),
                BotResponse.ButtonDto.callback("ℹ️", "SMARTLISTS_INFO")
        );
        return List.of(BotResponse.withMultiRowButtons(sb.toString().stripTrailing(), List.of(row)));
    }

    // --- create ---

    public List<BotResponse> promptCreate(ConversationContext ctx) {
        chatStateStore.put(ctx.conversationId(), FLOW_KEY_CREATE, true);
        return List.of(BotResponse.withButtons(
                "введи назву та правило через двокрапку, напр.:\nнічний джаз: жанр jazz і рейтинг > 3",
                Map.of("❌", "SMARTLISTS_ADD_CANCEL")));
    }

    public boolean isWaitingForCreate(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), FLOW_KEY_CREATE, Boolean.class).orElse(false);
    }

    public List<BotResponse> cancelCreate(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_CREATE);
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> handleCreateInput(ConversationContext ctx, String input) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_CREATE);
        String[] parts = input.split(":", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return List.of(BotResponse.text(
                    "не зрозумів — введи у форматі 'назва: правило', напр. 'нічний джаз: жанр jazz і рейтинг > 3'"));
        }
        SmartlistCreationFlowService.StartResult result =
                smartlistCreationFlowService.startCreate(ctx, parts[0].trim(), parts[1].trim());
        return result.responses();
    }

    // --- remove ---

    public List<BotResponse> promptRemove(ConversationContext ctx) {
        List<String> names = smartlistService.list().stream().map(SmartlistService.SmartlistSummary::name).toList();
        if (names.isEmpty()) {
            return List.of(BotResponse.text("смартлистів ще немає"));
        }
        chatStateStore.put(ctx.conversationId(), FLOW_KEY_REMOVE, new NameSelection(names));
        return List.of(BotResponse.withButtons(
                "🤔 введи номер(и) смартлистів для видалення через кому (напр. 1,3)",
                Map.of("❌", "SMARTLISTS_RM_CANCEL")));
    }

    public boolean isWaitingForRemove(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), FLOW_KEY_REMOVE, NameSelection.class).isPresent();
    }

    public List<BotResponse> cancelRemove(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_REMOVE);
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> handleRemoveInput(ConversationContext ctx, String input) {
        var sel = chatStateStore.get(ctx.conversationId(), FLOW_KEY_REMOVE, NameSelection.class);
        if (sel.isEmpty()) {
            return List.of(BotResponse.text("😔 сесія протухла — тисни 🗑 знову"));
        }
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_REMOVE);
        List<String> names = sel.get().names();

        List<Integer> numbers = parseNumbers(input);
        if (numbers == null) {
            return List.of(BotResponse.text("не зрозумів номери — введи через кому, напр. 1,3"));
        }
        if (numbers.isEmpty()) {
            return List.of(BotResponse.text("вкажи хоч один номер"));
        }
        for (int n : numbers) {
            if (n < 1 || n > names.size()) {
                return List.of(BotResponse.text("номер %d поза межами (усього %d)".formatted(n, names.size())));
            }
        }

        List<String> removed = new ArrayList<>();
        for (int n : new LinkedHashSet<>(numbers)) {
            String name = names.get(n - 1);
            if (smartlistService.delete(name)) {
                removed.add(name);
            }
        }
        return List.of(BotResponse.text("🗑 видалено: " + String.join(", ", removed)));
    }

    // --- info ---

    public List<BotResponse> promptInfo(ConversationContext ctx) {
        List<String> names = smartlistService.list().stream().map(SmartlistService.SmartlistSummary::name).toList();
        if (names.isEmpty()) {
            return List.of(BotResponse.text("смартлистів ще немає"));
        }
        chatStateStore.put(ctx.conversationId(), FLOW_KEY_INFO, new NameSelection(names));
        return List.of(BotResponse.withButtons(
                "🤔 введи номер смартлиста щоб глянути деталі",
                Map.of("❌", "SMARTLISTS_INFO_CANCEL")));
    }

    public boolean isWaitingForInfo(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), FLOW_KEY_INFO, NameSelection.class).isPresent();
    }

    public List<BotResponse> cancelInfo(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_INFO);
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> handleInfoInput(ConversationContext ctx, String input) {
        var sel = chatStateStore.get(ctx.conversationId(), FLOW_KEY_INFO, NameSelection.class);
        if (sel.isEmpty()) {
            return List.of(BotResponse.text("😔 сесія протухла — тисни ℹ️ знову"));
        }
        chatStateStore.remove(ctx.conversationId(), FLOW_KEY_INFO);
        List<String> names = sel.get().names();

        int n;
        try {
            n = Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("не зрозумів номер"));
        }
        if (n < 1 || n > names.size()) {
            return List.of(BotResponse.text("номер %d поза межами (усього %d)".formatted(n, names.size())));
        }

        String name = names.get(n - 1);
        var summary = smartlistService.list().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
        if (summary == null) {
            return List.of(BotResponse.text("смартлист вже не існує"));
        }
        String text = "🧠 " + summary.name()
                + "\n\n📐 правило: " + summary.dslDescription()
                + "\n🎵 треків: " + summary.trackCount();
        return List.of(BotResponse.text(text));
    }

    private List<Integer> parseNumbers(String input) {
        try {
            return Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        chatStateStore.remove(event.conversationId(), FLOW_KEY_CREATE);
        chatStateStore.remove(event.conversationId(), FLOW_KEY_REMOVE);
        chatStateStore.remove(event.conversationId(), FLOW_KEY_INFO);
    }

    private record NameSelection(List<String> names) {}
}
