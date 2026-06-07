package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import com.sashkomusic.events.ChatContextClearedEvent;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChatMemoryProvider для MainAgent. Кешує MessageWindowChatMemory по conversationId,
 * щоб in-memory view залишалась узгодженою зі store. Дає append-методи для cross-agent
 * context (коли /discovery або /library пишуть summary у memory MainAgent).
 * Automatically summarizes the main conversation when it approaches MAX_MESSAGES.
 */
@Slf4j
@Component("mainMemoryProvider")
@RequiredArgsConstructor
public class MainChatMemoryProvider implements ChatMemoryProvider {

    private static final int MAX_MESSAGES = 32;
    private static final int SUMMARIZE_THRESHOLD = 26;
    private static final int KEEP_RECENT = 10;

    private final ChatMemoryStore store;
    private final ChatModel haikuModel;
    private final ConcurrentHashMap<Object, ChatMemory> memories = new ConcurrentHashMap<>();

    @Override
    public ChatMemory get(Object memoryId) {
        ChatMemory memory = memories.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .maxMessages(MAX_MESSAGES)
                        .id(id)
                        .chatMemoryStore(store)
                        .build());
        String id = memoryId.toString();
        if (!id.endsWith(":d") && !id.endsWith(":lib")) {
            maybeSummarize(memoryId, memory);
        }
        return memory;
    }

    private void maybeSummarize(Object memoryId, ChatMemory memory) {
        List<ChatMessage> msgs = memory.messages();
        if (msgs.size() < SUMMARIZE_THRESHOLD) return;

        log.info("Summarizing memory for conversationId={}: {} messages", memoryId, msgs.size());
        int splitAt = msgs.size() - KEEP_RECENT;
        // Advance splitAt to the nearest UserMessage boundary to avoid breaking tool pairs
        while (splitAt < msgs.size() && !(msgs.get(splitAt) instanceof UserMessage)) {
            splitAt++;
        }
        if (splitAt >= msgs.size()) {
            log.warn("Could not find UserMessage boundary for summarization — skipping");
            return;
        }
        List<ChatMessage> toSummarize = msgs.subList(0, splitAt);
        List<ChatMessage> toKeep = new ArrayList<>(msgs.subList(splitAt, msgs.size()));
        String summaryText = callSummarize(toSummarize);
        memory.clear();
        memory.add(UserMessage.from("Підсумок попередньої розмови:"));
        memory.add(AiMessage.from(summaryText));
        for (ChatMessage m : toKeep) {
            memory.add(m);
        }
        log.info("Memory summarized for conversationId={}: {} → {} messages",
                memoryId, msgs.size(), 2 + toKeep.size());
    }

    private String callSummarize(List<ChatMessage> messages) {
        String conversationText = messages.stream()
                .map(m -> {
                    if (m instanceof UserMessage um) {
                        String text = um.contents().stream()
                                .filter(c -> c instanceof TextContent)
                                .map(c -> ((TextContent) c).text())
                                .collect(Collectors.joining(" "));
                        return text.isBlank() ? "" : "Користувач: " + text;
                    }
                    if (m instanceof AiMessage am) {
                        String text = am.text();
                        return (text != null && !text.isBlank()) ? "Бот: " + text : "";
                    }
                    return "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));

        String prompt = "Summarize this conversation in one compact paragraph (max 150 words), in English. "
                + "Focus on: what the user searched for, which artists/releases were discussed, "
                + "what actions were taken (downloads, processing, tagging), what was found.\n\n"
                + "Conversation:\n" + conversationText;
        try {
            var resp = haikuModel.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build());
            return resp.aiMessage().text();
        } catch (Exception e) {
            log.error("Summarization model call failed: {}", e.getMessage());
            return conversationText.substring(0, Math.min(800, conversationText.length()));
        }
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

    /** Clear chat memory across all agent suffixes ({@code ""}, {@code ":d"}, {@code ":lib"}) for one conversation. */
    public void clearAllForConversation(String conversationId) {
        clear(conversationId);
        store.deleteMessages(conversationId + ":d");
        store.deleteMessages(conversationId + ":lib");
    }

    @EventListener
    public void onContextCleared(ChatContextClearedEvent event) {
        clearAllForConversation(event.conversationId());
    }
}
