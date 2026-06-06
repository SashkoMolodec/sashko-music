package com.sashkomusic.agents.config;

import com.sashkomusic.mainagent.bot.state.ChatLogService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemory for MainAgent backed by chat_log table.
 * Loads user+assistant history from chat_log on construction (cross-agent context).
 * Delegates in-call tool tracking to MessageWindowChatMemory.
 * Persists only final assistant responses back to chat_log.
 */
public class ChatLogBackedChatMemory implements ChatMemory {

    private final Object memoryId;
    private final ChatLogService chatLogService;
    private final MessageWindowChatMemory delegate;

    public ChatLogBackedChatMemory(Object memoryId, int maxMessages, ChatLogService chatLogService) {
        this.memoryId = memoryId;
        this.chatLogService = chatLogService;

        List<ChatMessage> history = loadHistory(memoryId.toString(), maxMessages, chatLogService);
        this.delegate = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .id(memoryId)
                .build();
        history.forEach(delegate::add);
    }

    private static List<ChatMessage> loadHistory(String conversationId, int maxMessages,
                                                  ChatLogService chatLogService) {
        var entries = chatLogService.getLastN(conversationId, maxMessages);
        List<ChatMessage> messages = new ArrayList<>(entries.size());
        for (var entry : entries) {
            if ("user".equals(entry.role())) {
                messages.add(UserMessage.from(entry.content()));
            } else if ("assistant".equals(entry.role())) {
                messages.add(AiMessage.from(entry.content()));
            }
        }
        return messages;
    }

    @Override
    public Object id() {
        return memoryId;
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
        if (message instanceof AiMessage ai
                && (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty())
                && ai.text() != null && !ai.text().isBlank()) {
            chatLogService.log(memoryId.toString(), "assistant", ai.text(), "main");
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return delegate.messages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
