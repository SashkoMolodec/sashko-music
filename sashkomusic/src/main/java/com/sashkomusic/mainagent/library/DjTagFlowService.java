package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.mainagent.library.messaging.AddCommentTaskProducer;
import com.sashkomusic.mainagent.library.messaging.SetEnergyTaskProducer;
import com.sashkomusic.mainagent.library.messaging.SetFunctionTaskProducer;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
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
    private final DjTagContextHolder djTagContextHolder;

    public boolean isWaitingForComment(long chatId) {
        return djTagContextHolder.isWaitingForComment(chatId);
    }

    public List<BotResponse> handleCommentInput(long chatId, String commentText) {
        DjTagContextHolder.DjTagContext ctx = djTagContextHolder.getContext(chatId);
        if (ctx == null) {
            return List.of(BotResponse.text("помилка: контекст втрачено"));
        }

        djTagContextHolder.clearContext(chatId);
        return addComment(chatId, ctx.trackId(), commentText);
    }

    public List<BotResponse> expandDjRatePanel(long chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String navidromeId = parts[2];
            return buildDjRatePanel(chatId, trackId, navidromeId);
        } catch (NumberFormatException e) {
            log.error("Failed to parse expand callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    private List<BotResponse> buildDjRatePanel(long chatId, Long trackId, String navidromeId) {
        DjTagContextHolder.DjTagContext context = djTagContextHolder.getContext(chatId);
        TrackDto track = context != null ? context.track() : null;

        if (track == null || !track.id().equals(trackId)) {
            log.warn("Track context mismatch or not found for chat {}, trackId {}", chatId, trackId);
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

    public List<BotResponse> handleEnergyRate(long chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String energyLevel = parts[2];
            return setDjEnergy(chatId, trackId, energyLevel);
        } catch (NumberFormatException e) {
            log.error("Failed to parse energy callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> handleFunctionRate(long chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            String functionType = parts[2];
            return setDjFunction(chatId, trackId, functionType);
        } catch (NumberFormatException e) {
            log.error("Failed to parse function callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> handleCommentAdd(long chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            return List.of(BotResponse.text("невірний формат"));
        }

        try {
            djTagContextHolder.activateCommentMode(chatId);
            return List.of(BotResponse.text("✍️ шось туту во пиши:"));
        } catch (NumberFormatException e) {
            log.error("Failed to parse comment activation callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки"));
        }
    }

    public List<BotResponse> setDjEnergy(long chatId, Long trackId, String energyLevel) {
        log.info("Setting DJ energy {} for track {} from chatId={}", energyLevel, trackId, chatId);
        SetEnergyTaskDto task = new SetEnergyTaskDto(trackId, energyLevel, chatId);
        setEnergyTaskProducer.send(task);
        return Collections.emptyList();
    }

    public List<BotResponse> setDjFunction(long chatId, Long trackId, String functionType) {
        log.info("Setting DJ function {} for track {} from chatId={}", functionType, trackId, chatId);
        SetFunctionTaskDto task = new SetFunctionTaskDto(trackId, functionType, chatId);
        setFunctionTaskProducer.send(task);
        return List.of(BotResponse.text("⏳ маркуємо як " + functionType + "..."));
    }

    public List<BotResponse> addComment(long chatId, Long trackId, String comment) {
        log.info("Adding comment for track {} from chatId={}: {}", trackId, chatId, comment);
        AddCommentTaskDto task = new AddCommentTaskDto(trackId, comment, chatId);
        addCommentTaskProducer.send(task);
        return List.of(BotResponse.text("🗿 крутий"));
    }
}
