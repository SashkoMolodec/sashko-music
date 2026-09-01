package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.mainagent.library.messaging.AddCommentTaskProducer;
import com.sashkomusic.mainagent.library.messaging.ReplaceCommentTaskProducer;
import com.sashkomusic.mainagent.library.messaging.SetEnergyTaskProducer;
import com.sashkomusic.mainagent.library.messaging.SetFunctionTaskProducer;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
import com.sashkomusic.mainagent.library.messaging.dto.ReplaceCommentTaskDto;
import com.sashkomusic.mainagent.library.messaging.dto.SetEnergyTaskDto;
import com.sashkomusic.mainagent.library.messaging.dto.SetFunctionTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DjTagFlowService {

    private final SetEnergyTaskProducer setEnergyTaskProducer;
    private final SetFunctionTaskProducer setFunctionTaskProducer;
    private final AddCommentTaskProducer addCommentTaskProducer;
    private final ReplaceCommentTaskProducer replaceCommentTaskProducer;
    private final DjTagContextHolder djTagContextHolder;

    public boolean isWaitingForComment(ConversationContext ctx) {
        return djTagContextHolder.isWaitingForComment(ctx.conversationId());
    }

    public List<BotResponse> handleCommentInput(ConversationContext ctx, String commentText) {
        DjTagContextHolder.DjTagContext tagCtx = djTagContextHolder.getContext(ctx.conversationId());
        if (tagCtx == null) {
            return List.of(BotResponse.text("помилка: контекст втрачено"));
        }

        djTagContextHolder.deactivateCommentMode(ctx.conversationId());
        return addComment(ctx, tagCtx.trackId(), commentText);
    }

    public List<BotResponse> handleReplaceCommentInput(ConversationContext ctx, String commentText) {
        DjTagContextHolder.DjTagContext tagCtx = djTagContextHolder.getContext(ctx.conversationId());
        if (tagCtx == null) {
            return List.of(BotResponse.text("помилка: контекст втрачено"));
        }

        djTagContextHolder.deactivateCommentMode(ctx.conversationId());
        replaceCommentTaskProducer.send(new ReplaceCommentTaskDto(tagCtx.trackId(), commentText, ctx.conversationId()));
        return List.of(BotResponse.text("🗿 крутий"));
    }

    public List<BotResponse> handleEditCommentTrack(ConversationContext ctx, Long trackId) {
        DjTagContextHolder.DjTagContext tagCtx = djTagContextHolder.getContext(ctx.conversationId());
        String currentComment = tagCtx != null ? tagCtx.track().comment() : null;

        djTagContextHolder.activateReplaceMode(ctx.conversationId());

        if (currentComment == null || currentComment.isBlank()) {
            return List.of(BotResponse.text("коментів ще немає. введи новий:"));
        }
        return List.of(
                BotResponse.text(currentComment),
                BotResponse.text("✍️ скопіюй, підкоригуй")
        );
    }

    public DjTagContextHolder.DjTagContext getTagContext(ConversationContext ctx) {
        return djTagContextHolder.getContext(ctx.conversationId());
    }

    public List<BotResponse> expandDjRatePanel(ConversationContext ctx, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String navidromeId = parts[2];
            return buildDjRatePanel(ctx, trackId, navidromeId);
        } catch (NumberFormatException e) {
            log.error("Failed to parse expand callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    private List<BotResponse> buildDjRatePanel(ConversationContext ctx, Long trackId, String navidromeId) {
        DjTagContextHolder.DjTagContext context = djTagContextHolder.getContext(ctx.conversationId());
        TrackDto track = context != null ? context.track() : null;

        if (track == null || !track.id().equals(trackId)) {
            log.warn("Track context mismatch or not found for conversationId={}, trackId {}",
                    ctx.conversationId(), trackId);
            return List.of(BotResponse.text("помилка: контекст треку втрачено, спробуй /np знову"));
        }

        StringBuilder message = new StringBuilder("dj *pro* 🗿 режим");

        String comment = track.comment();
        if (comment != null && !comment.isEmpty()) {
            message.append("\n💬 ").append(comment);
        }

        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();

        List<BotResponse.ButtonDto> row2 = new ArrayList<>();
        row2.add(BotResponse.ButtonDto.callback("⚡ 1", "ENERGY_RATE:" + trackId + ":E1:" + navidromeId));
        row2.add(BotResponse.ButtonDto.callback("⚡ 2", "ENERGY_RATE:" + trackId + ":E2:" + navidromeId));
        row2.add(BotResponse.ButtonDto.callback("⚡ 3", "ENERGY_RATE:" + trackId + ":E3:" + navidromeId));
        row2.add(BotResponse.ButtonDto.callback("⚡ 4", "ENERGY_RATE:" + trackId + ":E4:" + navidromeId));
        row2.add(BotResponse.ButtonDto.callback("⚡ 5", "ENERGY_RATE:" + trackId + ":E5:" + navidromeId));
        rows.add(row2);

        List<BotResponse.ButtonDto> row3 = new ArrayList<>();
        row3.add(BotResponse.ButtonDto.callback("🌅", "FUNCTION_RATE:" + trackId + ":intro:" + navidromeId));
        row3.add(BotResponse.ButtonDto.callback("🔧", "FUNCTION_RATE:" + trackId + ":tool:" + navidromeId));
        row3.add(BotResponse.ButtonDto.callback("💥", "FUNCTION_RATE:" + trackId + ":banger:" + navidromeId));
        row3.add(BotResponse.ButtonDto.callback("🎆", "FUNCTION_RATE:" + trackId + ":closer:" + navidromeId));
        row3.add(BotResponse.ButtonDto.callback("💬", "ADD_COMMENT:" + trackId + ":" + navidromeId));
        rows.add(row3);

        return List.of(BotResponse.withMultiRowButtons(message.toString(), rows));
    }

    public List<BotResponse> handleEnergyRate(ConversationContext ctx, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String energyLevel = parts[2];
            return setDjEnergy(ctx, trackId, energyLevel);
        } catch (NumberFormatException e) {
            log.error("Failed to parse energy callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> handleFunctionRate(ConversationContext ctx, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String functionType = parts[2];
            return setDjFunction(ctx, trackId, functionType);
        } catch (NumberFormatException e) {
            log.error("Failed to parse function callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> handleCommentAdd(ConversationContext ctx, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            djTagContextHolder.activateCommentMode(ctx.conversationId());
            var buttons = List.of(
                    BotResponse.ButtonDto.callback("🏷", "LBL_LIST:T:" + trackId),
                    BotResponse.ButtonDto.callback("✏️", "EDIT_COMMENT:T:" + trackId),
                    BotResponse.ButtonDto.callback("❌", "COMMENT_CANCEL:T:" + trackId)
            );
            return List.of(BotResponse.withMultiRowButtons("✍️ шось туту во пиши:", List.of(buttons)));
        } catch (NumberFormatException e) {
            log.error("Failed to parse comment activation callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> handleCommentCancel(ConversationContext ctx) {
        djTagContextHolder.deactivateCommentMode(ctx.conversationId());
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> setDjEnergy(ConversationContext ctx, Long trackId, String energyLevel) {
        log.info("Setting DJ energy {} for track {} from conversationId={}", energyLevel, trackId, ctx.conversationId());
        SetEnergyTaskDto task = new SetEnergyTaskDto(trackId, energyLevel, ctx.conversationId());
        setEnergyTaskProducer.send(task);
        return Collections.emptyList();
    }

    public List<BotResponse> setDjFunction(ConversationContext ctx, Long trackId, String functionType) {
        log.info("Setting DJ function {} for track {} from conversationId={}", functionType, trackId, ctx.conversationId());
        SetFunctionTaskDto task = new SetFunctionTaskDto(trackId, functionType, ctx.conversationId());
        setFunctionTaskProducer.send(task);
        return List.of(BotResponse.text("⏳ маркуємо як " + functionType + "..."));
    }

    public List<BotResponse> addComment(ConversationContext ctx, Long trackId, String comment) {
        log.info("Adding comment for track {} from conversationId={}: {}", trackId, ctx.conversationId(), comment);
        AddCommentTaskDto task = new AddCommentTaskDto(trackId, comment, ctx.conversationId());
        addCommentTaskProducer.send(task);
        return List.of(BotResponse.text("🗿 крутий"));
    }
}
