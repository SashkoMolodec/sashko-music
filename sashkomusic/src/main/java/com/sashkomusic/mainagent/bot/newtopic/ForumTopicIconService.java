package com.sashkomusic.mainagent.bot.newtopic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.forum.GetForumTopicIconStickers;
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ForumTopicIconService {

    private final TelegramClient telegramClient;
    private final TopicEmojiPicker topicEmojiPicker;

    private volatile Map<String, String> emojiToId;

    public Optional<String> pickIconId(String topicName) {
        Map<String, String> icons = loadIcons();
        if (icons.isEmpty()) return Optional.empty();

        String availableEmojis = String.join(" ", icons.keySet());
        try {
            String picked = topicEmojiPicker.pickEmoji(topicName, availableEmojis).trim();
            return Optional.ofNullable(icons.get(picked));
        } catch (Exception e) {
            log.warn("Failed to pick topic emoji for '{}': {}", topicName, e.getMessage());
            return Optional.empty();
        }
    }

    private synchronized Map<String, String> loadIcons() {
        if (emojiToId == null) {
            try {
                List<Sticker> stickers = telegramClient.execute(new GetForumTopicIconStickers());
                emojiToId = stickers.stream()
                        .filter(s -> s.getEmoji() != null && s.getCustomEmojiId() != null)
                        .collect(Collectors.toMap(Sticker::getEmoji, Sticker::getCustomEmojiId, (a, b) -> a));
                log.info("Loaded {} forum topic icon stickers: {}", emojiToId.size(), emojiToId.keySet());
            } catch (Exception e) {
                log.warn("Failed to load forum topic icon stickers: {}", e.getMessage());
                emojiToId = Map.of();
            }
        }
        return emojiToId;
    }
}
