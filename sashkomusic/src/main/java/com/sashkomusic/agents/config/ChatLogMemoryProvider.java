package com.sashkomusic.agents.config;

import com.sashkomusic.mainagent.bot.state.ChatLogService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatMemoryProvider for MainAgent backed by chat_log table.
 * Maintains its own cache so that clear(memoryId) can evict in-memory state
 * without touching LangChain4j internals — both this cache and LangChain4j's
 * DefaultChatMemoryController hold the same object reference.
 */
@Component("chatLogMemoryProvider")
@RequiredArgsConstructor
public class ChatLogMemoryProvider implements ChatMemoryProvider {

    private static final int MAX_MESSAGES = 32;

    private final ChatLogService chatLogService;
    private final ConcurrentHashMap<Object, ChatLogBackedChatMemory> memories = new ConcurrentHashMap<>();

    @Override
    public ChatMemory get(Object memoryId) {
        return memories.computeIfAbsent(memoryId,
                id -> new ChatLogBackedChatMemory(id, MAX_MESSAGES, chatLogService));
    }

    public void clear(Object memoryId) {
        ChatLogBackedChatMemory memory = memories.get(memoryId);
        if (memory != null) {
            memory.clear();
        }
    }
}
