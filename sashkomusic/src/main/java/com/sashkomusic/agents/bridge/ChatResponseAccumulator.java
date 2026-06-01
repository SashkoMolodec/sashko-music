package com.sashkomusic.agents.bridge;

import com.sashkomusic.mainagent.bot.BotResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation buffer for BotResponses pushed from agent tools during a single
 * {@code MainAgent.chat()} invocation. Keyed by conversationId.
 */
@Slf4j
@Component
public class ChatResponseAccumulator {

    private final Map<String, List<BotResponse>> pending = new ConcurrentHashMap<>();

    public void begin(String conversationId) {
        List<BotResponse> stale = pending.remove(conversationId);
        if (stale != null && !stale.isEmpty()) {
            log.warn("Discarded {} stale responses for conversation={} — previous agent call did not drain", stale.size(), conversationId);
        }
    }

    public void push(String conversationId, BotResponse response) {
        pending.computeIfAbsent(conversationId, id -> new ArrayList<>()).add(response);
    }

    public void pushAll(String conversationId, List<BotResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        pending.computeIfAbsent(conversationId, id -> new ArrayList<>()).addAll(responses);
    }

    /** Replace all pending responses for this conversation (used when a newer search supersedes a previous one). */
    public void replaceAll(String conversationId, List<BotResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            pending.remove(conversationId);
            return;
        }
        pending.put(conversationId, new ArrayList<>(responses));
    }

    public List<BotResponse> drain(String conversationId) {
        List<BotResponse> taken = pending.remove(conversationId);
        return taken == null ? List.of() : taken;
    }
}
