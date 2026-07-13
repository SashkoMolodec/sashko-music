package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.search.FileIdCacheService;
import com.sashkomusic.mainagent.search.SearchSessionExpiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import org.slf4j.MDC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class TelegramChatBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final String FILE_ID_PREFIX = "FILE_ID:";
    private static final String LOCAL_FILE_PREFIX = "LOCAL_FILE:";
    private static final ReplyKeyboardMarkup DEFAULT_REPLY_KEYBOARD = buildDefaultReplyKeyboard();

    private static ReplyKeyboardMarkup buildDefaultReplyKeyboard() {
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton("шо грає 🎵"));
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(row)
                .resizeKeyboard(true)
                .isPersistent(true)
                .selective(false)
                .build();
    }

    private final UserInteractionOrchestrator orchestrator;
    private final FileIdCacheService fileIdCacheService;
    private final TelegramClient client;
    private final String botToken;
    private final Long allowedGroupId;
    private final Long defaultChatId;

    public TelegramChatBot(@Value("${telegram.bot.token}") String token,
                           @Value("${telegram.allowed-group-id:}") String allowedGroupIdStr,
                           @Value("${telegram.default-chat-id:0}") Long defaultChatId,
                           UserInteractionOrchestrator orchestrator,
                           FileIdCacheService fileIdCacheService,
                           TelegramClient telegramClient) {
        this.botToken = token;
        this.client = telegramClient;
        this.orchestrator = orchestrator;
        this.fileIdCacheService = fileIdCacheService;
        this.allowedGroupId = allowedGroupIdStr.isBlank() ? null : Long.parseLong(allowedGroupIdStr);
        this.defaultChatId = defaultChatId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (defaultChatId == null || defaultChatId == 0) return;
        sendMessage(defaultChatId, "я знову тутка 👀");
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        MDC.put("flowId", String.valueOf(update.getUpdateId()));
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                Message msg = update.getMessage();
                ConversationContext ctx = buildContext(msg.getChatId(), msg.getMessageThreadId());
                if (!isAllowed(ctx)) return;

                var text = msg.getText();
                log.info("📩 Text from [{}]: {}", ctx.conversationId(), text);

                orchestrator.handleUserRequest(ctx, text)
                        .forEach(res -> sendResponse(ctx, res));

            } else if (update.hasCallbackQuery()) {
                var callback = update.getCallbackQuery();
                var data = callback.getData();
                Message msg = (Message) callback.getMessage();
                ConversationContext ctx = buildContext(msg.getChatId(), msg.getMessageThreadId());
                if (!isAllowed(ctx)) return;

                log.info("👆 Click from [{}]: {}", ctx.conversationId(), data);
                answerCallback(callback.getId());

                Integer sourceMessageId = msg.getMessageId();
                orchestrator.handleCallback(ctx, data, sourceMessageId)
                        .forEach(response -> sendResponse(ctx, response));
            }
        } catch (SearchSessionExpiredException e) {
            log.warn("Session expired: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in consumer: ", e);
        } finally {
            MDC.remove("flowId");
        }
    }

    private ConversationContext buildContext(long chatId, Integer threadId) {
        if (threadId != null && threadId > 0) {
            return ConversationContext.topic(chatId, threadId);
        }
        return ConversationContext.dm(chatId);
    }

    private boolean isAllowed(ConversationContext ctx) {
        if (allowedGroupId == null) return true;
        // Always allow DMs; for group messages check the group ID
        if (!ctx.isGroupTopic()) return true;
        return ctx.chatId() == allowedGroupId;
    }

    public void sendResponse(ConversationContext ctx, BotResponse response) {
        var keyboardMarkup = createKeyboard(response.buttons(), response.buttonRows());
        ReplyKeyboard outgoingMarkup = keyboardMarkup != null ? keyboardMarkup : DEFAULT_REPLY_KEYBOARD;
        String formattedText = response.preformatted()
                ? response.text()
                : TelegramHtmlFormatter.format(response.text());
        boolean hasImage = response.imageUrl() != null && !response.imageUrl().isBlank();

        if (response.editMessageId() != null) {
            editExistingMessage(ctx, response, formattedText, hasImage, keyboardMarkup);
            return;
        }

        if (hasImage) {
            try {
                InputFile photo = buildInputFile(response.imageUrl());
                SendPhoto.SendPhotoBuilder<?, ?> photoBuilder = SendPhoto.builder()
                        .chatId(ctx.chatId())
                        .photo(photo)
                        .caption(formattedText)
                        .parseMode("HTML")
                        .replyMarkup(outgoingMarkup);
                if (ctx.isGroupTopic()) {
                    photoBuilder.messageThreadId(ctx.topicId());
                }
                Message sent = client.execute(photoBuilder.build());
                cachePhotoFileId(ctx, response.imageUrl(), sent);
                return;
            } catch (TelegramApiException e) {
                log.warn("⚠️ Failed to send photo to [{}]. URL: {}. Error: {}",
                        ctx.conversationId(), response.imageUrl(), e.getMessage());
            }
        }

        try {
            SendMessage.SendMessageBuilder<?, ?> msgBuilder = SendMessage.builder()
                    .chatId(ctx.chatId())
                    .text(formattedText)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .replyMarkup(outgoingMarkup);
            if (ctx.isGroupTopic()) {
                msgBuilder.messageThreadId(ctx.topicId());
            }
            client.execute(msgBuilder.build());
        } catch (TelegramApiException e) {
            log.error("❌ Failed to send with HTML parsing to [{}]: {}. Retrying as plain text",
                    ctx.conversationId(), e.getMessage());

            try {
                SendMessage.SendMessageBuilder<?, ?> plainBuilder = SendMessage.builder()
                        .chatId(ctx.chatId())
                        .text(response.text())
                        .disableWebPagePreview(true)
                        .replyMarkup(outgoingMarkup);
                if (ctx.isGroupTopic()) {
                    plainBuilder.messageThreadId(ctx.topicId());
                }
                client.execute(plainBuilder.build());
                log.info("✅ Successfully sent as plain text");
            } catch (TelegramApiException ex) {
                log.error("❌ Failed to send even as plain text to [{}]: {}", ctx.conversationId(), ex.getMessage());
            }
        }
    }

    private void editExistingMessage(ConversationContext ctx, BotResponse response,
                                     String formattedText, boolean hasImage,
                                     InlineKeyboardMarkup keyboardMarkup) {
        int messageId = response.editMessageId();
        try {
            if (hasImage) {
                boolean isFileId = response.imageUrl().startsWith(FILE_ID_PREFIX);
                String mediaRef = isFileId ? response.imageUrl().substring(FILE_ID_PREFIX.length())
                                           : response.imageUrl();
                InputMediaPhoto media = InputMediaPhoto.builder()
                        .media(mediaRef)
                        .caption(formattedText)
                        .parseMode("HTML")
                        .build();
                var result = client.execute(EditMessageMedia.builder()
                        .chatId(ctx.chatId())
                        .messageId(messageId)
                        .media(media)
                        .replyMarkup(keyboardMarkup)
                        .build());
                if (!isFileId && result instanceof Message msg) {
                    cachePhotoFileId(ctx, response.imageUrl(), msg);
                }
            } else {
                client.execute(EditMessageText.builder()
                        .chatId(ctx.chatId())
                        .messageId(messageId)
                        .text(formattedText)
                        .parseMode("HTML")
                        .disableWebPagePreview(true)
                        .replyMarkup(keyboardMarkup)
                        .build());
            }
        } catch (TelegramApiException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("message is not modified")) {
                return;
            }
            log.warn("Failed to edit message {} in [{}]: {}. Falling back to caption edit.",
                    messageId, ctx.conversationId(), msg);
            try {
                client.execute(EditMessageCaption.builder()
                        .chatId(ctx.chatId())
                        .messageId(messageId)
                        .caption(formattedText)
                        .parseMode("HTML")
                        .replyMarkup(keyboardMarkup)
                        .build());
            } catch (TelegramApiException ex) {
                log.warn("Failed to edit caption for message {} in [{}]: {}. Falling back to text edit.",
                        messageId, ctx.conversationId(), ex.getMessage());
                try {
                    client.execute(EditMessageText.builder()
                            .chatId(ctx.chatId())
                            .messageId(messageId)
                            .text(formattedText)
                            .parseMode("HTML")
                            .disableWebPagePreview(true)
                            .replyMarkup(keyboardMarkup)
                            .build());
                } catch (TelegramApiException ex2) {
                    log.error("❌ Failed to edit message {} in [{}]: {}",
                            messageId, ctx.conversationId(), ex2.getMessage());
                }
            }
        }
    }

    private InputFile buildInputFile(String imageUrl) {
        if (imageUrl.startsWith(LOCAL_FILE_PREFIX)) {
            return new InputFile(new File(imageUrl.substring(LOCAL_FILE_PREFIX.length())));
        }
        return new InputFile(imageUrl);
    }

    private void cachePhotoFileId(ConversationContext ctx, String imageUrl, Message message) {
        if (message == null || message.getPhoto() == null || message.getPhoto().isEmpty()) return;
        String fileId = message.getPhoto().stream()
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)
                .map(p -> p.getFileId())
                .orElse(null);
        if (fileId != null) {
            fileIdCacheService.put(ctx.conversationId(), imageUrl, fileId);
        }
    }

    public void sendMessage(ConversationContext ctx, String text) {
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            List<String> chunks = splitTextIntoChunks(text, MAX_TEXT_LENGTH);
            for (String chunk : chunks) {
                sendResponse(ctx, BotResponse.text(chunk));
            }
        } else {
            sendResponse(ctx, BotResponse.text(text));
        }
    }

    /** Convenience overload for async listeners that only know chatId (DM context assumed). */
    public void sendMessage(long chatId, String text) {
        sendMessage(ConversationContext.dm(chatId), text);
    }

    private InlineKeyboardMarkup createKeyboard(Map<String, String> buttons, List<List<BotResponse.ButtonDto>> buttonRows) {
        if (buttonRows != null && !buttonRows.isEmpty()) {
            List<InlineKeyboardRow> rows = new ArrayList<>();
            for (List<BotResponse.ButtonDto> row : buttonRows) {
                List<InlineKeyboardButton> rowButtons = new ArrayList<>();
                for (BotResponse.ButtonDto btn : row) {
                    var builder = InlineKeyboardButton.builder().text(btn.label());
                    String data = btn.callbackData();
                    if (data != null && data.startsWith("URL:")) {
                        builder.url(data.substring(4));
                    } else {
                        builder.callbackData(data);
                    }
                    rowButtons.add(builder.build());
                }
                rows.add(new InlineKeyboardRow(rowButtons));
            }
            return new InlineKeyboardMarkup(rows);
        }

        if (buttons == null || buttons.isEmpty()) {
            return null;
        }

        List<InlineKeyboardButton> rowButtons = new ArrayList<>();
        for (var entry : buttons.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue();
            var buttonBuilder = InlineKeyboardButton.builder().text(label);

            if (value.startsWith("URL:")) {
                buttonBuilder.url(value.substring(4));
            } else {
                buttonBuilder.callbackData(value);
            }
            rowButtons.add(buttonBuilder.build());
        }
        return new InlineKeyboardMarkup(List.of(new InlineKeyboardRow(rowButtons)));
    }

    private void answerCallback(String queryId) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(queryId)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("⚠️ Could not answer callback: {}", e.getMessage());
        }
    }

    private List<String> splitTextIntoChunks(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        String remaining = text;
        while (remaining.length() > maxLength) {
            int splitIndex = maxLength;

            int lastNewline = remaining.lastIndexOf('\n', maxLength);
            if (lastNewline > 0 && lastNewline > maxLength * 0.5) {
                splitIndex = lastNewline;
            }

            chunks.add(remaining.substring(0, splitIndex));
            remaining = remaining.substring(splitIndex).stripLeading();
        }

        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }

        return chunks;
    }
}
