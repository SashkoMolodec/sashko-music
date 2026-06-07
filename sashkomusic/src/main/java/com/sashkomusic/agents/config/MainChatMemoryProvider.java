package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatMemoryProvider для MainAgent. Кешує MessageWindowChatMemory по conversationId,
 * щоб in-memory view залишалась узгодженою зі store. Дає append-методи для cross-agent
 * context (коли /discovery або /library пишуть summary у memory MainAgent).
 */
@Component("mainMemoryProvider")
@RequiredArgsConstructor
public class MainChatMemoryProvider implements ChatMemoryProvider {

    private static final int MAX_MESSAGES = 32;

    private final ChatMemoryStore store;
    private final ConcurrentHashMap<Object, ChatMemory> memories = new ConcurrentHashMap<>();

    @Override
    public ChatMemory get(Object memoryId) {
        return memories.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .maxMessages(MAX_MESSAGES)
                        .id(id)
                        .chatMemoryStore(store)
                        .build());
    }

    public void appendUserAndAi(String conversationId, String userText, String aiText) {
        if (userText == null || aiText == null) return;
        ChatMemory memory = get(conversationId);
        memory.add(UserMessage.from(userText));
        memory.add(AiMessage.from(aiText));
    }

    public void clear(Object memoryId) {
        ChatMemory memory = memories.remove(memoryId);
        if (memory != null) memory.clear();
        store.deleteMessages(memoryId);
    }
}
