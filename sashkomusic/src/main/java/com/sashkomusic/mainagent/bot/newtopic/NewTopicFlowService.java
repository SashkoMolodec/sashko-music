package com.sashkomusic.mainagent.bot.newtopic;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewTopicFlowService {

    private final TelegramClient telegramClient;
    private final ConversationTopicStore topicStore;
    private final TopicNameGenerator topicNameGenerator;
    private final ChatMemoryStore chatMemoryStore;
    private final ForumTopicIconService forumTopicIconService;

    /** @param topicName already extracted from the command, empty string means auto-generate */
    public List<BotResponse> handle(ConversationContext ctx, String topicName) {
        if (topicName.isBlank()) {
            topicName = generateNameFromMemory(ctx.conversationId());
        }

        try {
            String iconId = forumTopicIconService.pickIconId(topicName).orElse(null);
            ForumTopic created = telegramClient.execute(CreateForumTopic.builder()
                    .chatId(String.valueOf(ctx.chatId()))
                    .name(topicName)
                    .iconCustomEmojiId(iconId)
                    .build());
            int newTopicId = created.getMessageThreadId();

            topicStore.save(ctx.chatId(), newTopicId, topicName);

            String newConversationId = ConversationContext.topic(ctx.chatId(), newTopicId).conversationId();
            seedNewTopicMemory(ctx.conversationId(), newConversationId);
            chatMemoryStore.deleteMessages(ctx.conversationId());

            String summary = generateOpeningSummary(topicName, ctx.conversationId());
            sendToNewTopic(ctx.chatId(), newTopicId, summary);

            log.info("Created forum topic '{}' (threadId={}) for chat={}", topicName, newTopicId, ctx.chatId());
            return List.of(BotResponse.text("✅ створив «" + topicName + "» — продовжуй там"));

        } catch (TelegramApiException e) {
            log.error("Failed to create forum topic '{}' for chat={}: {}", topicName, ctx.chatId(), e.getMessage());
            return List.of(BotResponse.text("❌ не вдалося створити топік: " + e.getMessage()));
        }
    }

    private String generateNameFromMemory(String conversationId) {
        String context = buildContextFromAllMemories(conversationId);
        if (context.isBlank()) return "новий чат";
        try {
            return topicNameGenerator.generateName(context);
        } catch (Exception e) {
            log.warn("Failed to generate topic name: {}", e.getMessage());
            return "новий чат";
        }
    }

    private String generateOpeningSummary(String topicName, String conversationId) {
        String context = buildContextFromAllMemories(conversationId);
        if (context.isBlank()) return topicName;
        try {
            return topicNameGenerator.generateOpeningSummary(topicName, context);
        } catch (Exception e) {
            log.warn("Failed to generate opening summary: {}", e.getMessage());
            return topicName;
        }
    }

    private void seedNewTopicMemory(String sourceConversationId, String newConversationId) {
        List<ChatMessage> sourceMessages = chatMemoryStore.getMessages(sourceConversationId);
        List<ChatMessage> seedMessages = sourceMessages.stream()
                .filter(msg -> switch (msg.type()) {
                    case USER -> true;
                    case AI -> ((AiMessage) msg).toolExecutionRequests().isEmpty();
                    default -> false;
                })
                .collect(Collectors.toList());
        if (!seedMessages.isEmpty()) {
            chatMemoryStore.updateMessages(newConversationId, seedMessages);
            log.info("Seeded new topic {} with {} messages from {}", newConversationId, seedMessages.size(), sourceConversationId);
        }
    }

    private String buildContextFromAllMemories(String conversationId) {
        for (String id : List.of(conversationId, conversationId + ":d", conversationId + ":lib")) {
            String context = buildContextString(chatMemoryStore.getMessages(id));
            if (!context.isBlank()) return context;
        }
        return "";
    }

    private String buildContextString(List<ChatMessage> messages) {
        return messages.stream()
                .limit(10)
                .map(msg -> switch (msg.type()) {
                    case USER -> "user: " + ((UserMessage) msg).singleText();
                    case AI -> "assistant: " + ((AiMessage) msg).text();
                    default -> null;
                })
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private void sendToNewTopic(long chatId, int topicId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .messageThreadId(topicId)
                    .text(com.sashkomusic.mainagent.bot.TelegramHtmlFormatter.format(text))
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send opening summary to new topic {}: {}", topicId, e.getMessage());
        }
    }
}
