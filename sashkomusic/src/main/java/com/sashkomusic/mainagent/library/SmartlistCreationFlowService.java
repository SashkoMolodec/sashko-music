package com.sashkomusic.mainagent.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDraft;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDsl;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDslExtractor;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartlistCreationFlowService {

    private static final String CB_CONFIRM = "SM:OK";
    private static final String CB_CANCEL = "SM:NO";
    private static final int PREVIEW_LIMIT = 5;

    private final SmartlistService smartlistService;
    private final SmartlistDslExtractor dslExtractor;
    private final ChatStateStore chatStateStore;
    private final ObjectMapper objectMapper;

    /**
     * Outcome of a smartlist creation attempt. {@code responses} go to the chat;
     * {@code agentSummary} is what the LLM tool sees as its return value — must
     * truthfully reflect success / failure so the model does not narrate over an error.
     */
    public record StartResult(boolean drafted, List<BotResponse> responses, String agentSummary) {}

    public StartResult startCreate(ConversationContext ctx, String name, String naturalDescription) {
        if (name == null || name.isBlank()) {
            return new StartResult(false, List.of(BotResponse.text("❌ потрібна назва смартлиста")),
                    "не створено: пуста назва смартлиста");
        }
        SmartlistDsl dsl = extract(naturalDescription, null);
        if (dsl == null || dsl.conditions().isEmpty()) {
            return new StartResult(false,
                    List.of(BotResponse.text("❌ не зміг розібрати умови — спробуй сформулювати інакше")),
                    "не створено: не вдалось розібрати правила смартлиста — попроси юзера переформулювати");
        }
        SmartlistDraft draft = new SmartlistDraft(name.trim(), dsl);
        chatStateStore.put(ctx.conversationId(), SmartlistDraft.FLOW_KEY, draft);
        return new StartResult(true, buildPreviewCard(draft),
                "показав картку підтвердження смартлиста '" + draft.name() + "'");
    }

    public boolean hasDraft(ConversationContext ctx) {
        return chatStateStore.get(ctx.conversationId(), SmartlistDraft.FLOW_KEY, SmartlistDraft.class).isPresent();
    }

    public List<BotResponse> refine(ConversationContext ctx, String userInput) {
        Optional<SmartlistDraft> opt = chatStateStore.get(ctx.conversationId(), SmartlistDraft.FLOW_KEY, SmartlistDraft.class);
        if (opt.isEmpty()) return List.of();

        String trimmed = userInput == null ? "" : userInput.trim().toLowerCase();
        if (trimmed.equals("ок") || trimmed.equals("ok") || trimmed.equals("так") || trimmed.equals("yes")) {
            return confirm(ctx);
        }
        if (trimmed.equals("скасуй") || trimmed.equals("стоп") || trimmed.equals("cancel") || trimmed.equals("ні") || trimmed.equals("no")) {
            return cancel(ctx);
        }

        SmartlistDraft current = opt.get();
        SmartlistDsl updated = extract(userInput, current.dsl());
        if (updated == null || updated.conditions().isEmpty()) {
            return List.of(BotResponse.text("❌ не зміг оновити умови — спробуй ще раз або напиши 'ок' щоб підтвердити"));
        }
        SmartlistDraft next = new SmartlistDraft(current.name(), updated);
        chatStateStore.put(ctx.conversationId(), SmartlistDraft.FLOW_KEY, next);
        return buildPreviewCard(next);
    }

    public List<BotResponse> handleConfirm(ConversationContext ctx, String callbackData) {
        return confirm(ctx);
    }

    public List<BotResponse> handleCancel(ConversationContext ctx, String callbackData) {
        return cancel(ctx);
    }

    private List<BotResponse> confirm(ConversationContext ctx) {
        Optional<SmartlistDraft> opt = chatStateStore.get(ctx.conversationId(), SmartlistDraft.FLOW_KEY, SmartlistDraft.class);
        if (opt.isEmpty()) return List.of(BotResponse.text("❌ нема чорнетки смартлиста"));
        SmartlistDraft draft = opt.get();
        try {
            SmartlistService.SmartlistSummary summary = smartlistService.create(draft.name(), draft.dsl());
            chatStateStore.remove(ctx.conversationId(), SmartlistDraft.FLOW_KEY);
            return List.of(BotResponse.text("✅ смартлист '" + summary.name() + "' створено, " + summary.trackCount() + " треків"));
        } catch (IllegalArgumentException e) {
            return List.of(BotResponse.text("❌ " + e.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to create smartlist '{}': {}", draft.name(), e.getMessage(), e);
            return List.of(BotResponse.text("❌ не вдалось створити смартлист: " + e.getMessage()));
        }
    }

    private List<BotResponse> cancel(ConversationContext ctx) {
        chatStateStore.remove(ctx.conversationId(), SmartlistDraft.FLOW_KEY);
        return List.of(BotResponse.text("✅ скасовано"));
    }

    private SmartlistDsl extract(String userText, SmartlistDsl previous) {
        try {
            String prev = previous == null ? "none" : objectMapper.writeValueAsString(previous);
            String json = dslExtractor.extractJson(userText, prev);
            return smartlistService.parse(json);
        } catch (Exception e) {
            log.warn("Failed to extract smartlist DSL from '{}': {}", userText, e.getMessage());
            return null;
        }
    }

    private List<BotResponse> buildPreviewCard(SmartlistDraft draft) {
        List<Track> preview = smartlistService.previewTracks(draft.dsl(), PREVIEW_LIMIT);
        StringBuilder sb = new StringBuilder();
        sb.append("🧠 смартлист: <b>").append(escape(draft.name())).append("</b>\n");
        sb.append("📐 правило: <code>").append(escape(smartlistService.describe(draft.dsl()))).append("</code>\n\n");
        if (preview.isEmpty()) {
            sb.append("⚠️ під ці умови не підпадає жоден трек — підправ правило або підтверди порожній смартлист");
        } else {
            sb.append("приклад треків (").append(preview.size()).append("):\n");
            for (Track t : preview) {
                String artist = t.getArtists().stream().findFirst().map(Artist::getName).orElse("?");
                sb.append("• ").append(escape(artist)).append(" — ").append(escape(t.getTitle())).append("\n");
            }
            sb.append("\nможеш уточнити правило текстом або підтвердити кнопкою");
        }
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("✅ створити", CB_CONFIRM);
        buttons.put("❌ скасувати", CB_CANCEL);
        return List.of(BotResponse.htmlWithButtons(sb.toString(), buttons));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
