package com.sashkomusic.agents.bridge;

import com.sashkomusic.mainagent.bot.BotResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chatId buffer for BotResponses pushed from agent tools during a single
 * {@code MainAgent.chat()} invocation.
 *
 * Lifecycle per agent call:
 *   begin(chatId)  — clears any stale responses left from a previous failed call
 *   [agent runs, tools push() responses]
 *   drain(chatId)  — returns everything pushed during this call
 */
@Slf4j
@Component
public class ChatResponseAccumulator {

    private final Map<Long, List<BotResponse>> pending = new ConcurrentHashMap<>();

    public void begin(long chatId) {
        List<BotResponse> stale = pending.remove(chatId);
        if (stale != null && !stale.isEmpty()) {
            log.warn("Discarded {} stale responses for chatId={} — previous agent call did not drain", stale.size(), chatId);
        }
    }

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
