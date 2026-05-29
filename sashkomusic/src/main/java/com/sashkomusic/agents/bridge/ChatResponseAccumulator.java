package com.sashkomusic.agents.bridge;

import com.sashkomusic.mainagent.bot.BotResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chatId buffer for BotResponses pushed from agent tools during a single
 * {@code MainAgent.chat()} invocation. The Telegram bot drains it after the
 * call completes and sends each response to the user.
 */
@Component
public class ChatResponseAccumulator {

    private final Map<Long, List<BotResponse>> pending = new ConcurrentHashMap<>();

    public void push(long chatId, BotResponse response) {
        pending.computeIfAbsent(chatId, id -> new ArrayList<>()).add(response);
    }

    public void pushAll(long chatId, List<BotResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        pending.computeIfAbsent(chatId, id -> new ArrayList<>()).addAll(responses);
    }

    public List<BotResponse> drain(long chatId) {
        List<BotResponse> taken = pending.remove(chatId);
        return taken == null ? List.of() : taken;
    }
}
