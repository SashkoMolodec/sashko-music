package com.sashkomusic.mainagent.bot;

import java.util.List;
import java.util.Map;

public record BotResponse(
        String text,
        String imageUrl,
        Map<String, String> buttons,
        List<List<ButtonDto>> buttonRows,
        Integer editMessageId,
        boolean preformatted
) {
    public static BotResponse text(String text) {
        return new BotResponse(text, null, null, null, null, false);
    }

    public static BotResponse aiText(String text) {
        return new BotResponse("🤖 _" + text + "_", null, null, null, null, false);
    }

    public static BotResponse withButtons(String text, Map<String, String> buttons) {
        return new BotResponse(text, null, buttons, null, null, false);
    }

    public static BotResponse htmlWithButtons(String html, Map<String, String> buttons) {
        return new BotResponse(html, null, buttons, null, null, true);
    }

    public static BotResponse card(String text, String imageUrl, Map<String, String> buttons) {
        return new BotResponse(text, imageUrl, buttons, null, null, false);
    }

    public static BotResponse cardWithRows(String text, String imageUrl, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, imageUrl, null, buttonRows, null, false);
    }

    public static BotResponse editCard(int messageId, String text, String imageUrl, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, imageUrl, null, buttonRows, messageId, false);
    }

    public static BotResponse withMultiRowButtons(String text, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, null, null, buttonRows, null, false);
    }

    public record ButtonDto(String label, String callbackData) {
        public static ButtonDto callback(String label, String callbackData) {
            return new ButtonDto(label, callbackData);
        }
    }
}
