package com.sashkomusic.mainagent.bot;

import java.util.List;
import java.util.Map;

/**
 * @param editMessageId edit this existing message instead of sending a new one
 * @param rememberAs    flow key under which {@code TelegramChatBot} stores the id of the message it
 *                      ended up sending, so a later response can edit that same message in place
 */
public record BotResponse(
        String text,
        String imageUrl,
        Map<String, String> buttons,
        List<List<ButtonDto>> buttonRows,
        Integer editMessageId,
        boolean preformatted,
        String rememberAs
) {
    public static BotResponse text(String text) {
        return new BotResponse(text, null, null, null, null, false, null);
    }

    public static BotResponse aiText(String text) {
        return new BotResponse("🤖 _" + text + "_", null, null, null, null, false, null);
    }

    public static BotResponse withButtons(String text, Map<String, String> buttons) {
        return new BotResponse(text, null, buttons, null, null, false, null);
    }

    public static BotResponse htmlWithButtons(String html, Map<String, String> buttons) {
        return new BotResponse(html, null, buttons, null, null, true, null);
    }

    public static BotResponse card(String text, String imageUrl, Map<String, String> buttons) {
        return new BotResponse(text, imageUrl, buttons, null, null, false, null);
    }

    public static BotResponse cardWithRows(String text, String imageUrl, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, imageUrl, null, buttonRows, null, false, null);
    }

    public static BotResponse editCard(int messageId, String text, String imageUrl, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, imageUrl, null, buttonRows, messageId, false, null);
    }

    public static BotResponse withMultiRowButtons(String text, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, null, null, buttonRows, null, false, null);
    }

    /**
     * Reuses one message slot per conversation: edits {@code previousMessageId} when it still
     * exists, otherwise sends fresh — either way the resulting id is stored under {@code flowKey}.
     */
    public static BotResponse reusingMessage(Integer previousMessageId, String flowKey, String text) {
        return new BotResponse(text, null, null, null, previousMessageId, false, flowKey);
    }

    public record ButtonDto(String label, String callbackData) {
        public static ButtonDto callback(String label, String callbackData) {
            return new ButtonDto(label, callbackData);
        }
    }
}
