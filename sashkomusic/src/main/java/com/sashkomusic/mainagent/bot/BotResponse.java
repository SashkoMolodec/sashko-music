package com.sashkomusic.mainagent.bot;

import java.util.List;
import java.util.Map;

public record BotResponse(
        String text,
        String imageUrl,
        Map<String, String> buttons,
        List<List<ButtonDto>> buttonRows
) {
    public static BotResponse text(String text) {
        return new BotResponse(text, null, null, null);
    }

    public static BotResponse withButtons(String text, Map<String, String> buttons) {
        return new BotResponse(text, null, buttons, null);
    }

    public static BotResponse card(String text, String imageUrl, Map<String, String> buttons) {
        return new BotResponse(text, imageUrl, buttons, null);
    }

    public static BotResponse withMultiRowButtons(String text, List<List<ButtonDto>> buttonRows) {
        return new BotResponse(text, null, null, buttonRows);
    }

    public record ButtonDto(String label, String callbackData) {
        public static ButtonDto callback(String label, String callbackData) {
            return new ButtonDto(label, callbackData);
        }
    }
}