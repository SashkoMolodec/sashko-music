package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.TagChangesNotificationEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TagChangesNotificationListener {

    private final TelegramChatBot chatBot;
    private final NavidromeClient navidromeClient;

    @Value("${telegram.default-chat-id}")
    private Long defaultChatId;

    @EventListener
    @Async
    public void handleTagChanges(TagChangesNotificationEvent event) {
        TagChangesNotificationDto notification = event.payload();
        log.info("Received tag changes notification: {} tracks, {} total changes",
                notification.tracks().size(), notification.totalChanges());

        String message = buildNotificationMessage(notification);
        chatBot.sendMessage(defaultChatId, message);
        syncRatingToNavidrome(notification);
    }

    private void syncRatingToNavidrome(TagChangesNotificationDto notification) {
        for (TagChangesNotificationDto.TrackChanges track : notification.tracks()) {
            for (TagChangesNotificationDto.TagChangeInfo change : track.changes()) {
                if (isRatingChange(change.tagName())) {
                    String newValue = change.newValue();
                    if (newValue != null && !newValue.isEmpty()) {
                        updateNavidromeRating(track.artistName(), track.trackTitle(), newValue);
                    }
                }
            }
        }
    }

    private boolean isRatingChange(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "RATING".equals(upperTag) || "RATING WMP".equals(upperTag) || "TXXX:RATING".equals(upperTag);
    }

    private void updateNavidromeRating(String artist, String title, String ratingValue) {
        try {
            int wmpRating = Integer.parseInt(ratingValue);
            int navidromeRating = convertWmpToNavidromeRating(wmpRating);
            if (navidromeRating == 0) {
                log.debug("Skipping rating update for {} - {} (WMP rating is 0)", artist, title);
                return;
            }
            String navidromeId = navidromeClient.findTrackIdByArtistAndTitle(artist, title);
            if (navidromeId != null) {
                navidromeClient.setRating(navidromeId, navidromeRating);
                log.info("✓ Synced rating to Navidrome: {} - {} = {} stars", artist, title, navidromeRating);
            } else {
                log.warn("Could not find track in Navidrome to sync rating: {} - {}", artist, title);
            }
        } catch (Exception e) {
            log.error("Failed to update Navidrome rating for {} - {}: {}", artist, title, e.getMessage());
        }
    }

    private int convertWmpToNavidromeRating(int wmpRating) {
        if (wmpRating == 0) return 0;
        if (wmpRating <= 51) return 1;
        if (wmpRating <= 102) return 2;
        if (wmpRating <= 153) return 3;
        if (wmpRating <= 204) return 4;
        return 5;
    }

    private String buildNotificationMessage(TagChangesNotificationDto notification) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎵 **оновлено теги треків**\n\n");
        for (TagChangesNotificationDto.TrackChanges track : notification.tracks()) {
            sb.append("📀 _").append(track.artistName().toLowerCase())
              .append(" — ").append(track.trackTitle().toLowerCase()).append("_\n");
            for (TagChangesNotificationDto.TagChangeInfo change : track.changes()) {
                String tagDisplay = formatTagName(change.tagName());
                String oldValueDisplay = formatTagValue(change.tagName(), change.oldValue());
                String newValueDisplay = formatTagValue(change.tagName(), change.newValue());
                if (change.isNew()) {
                    sb.append("   ➕ ").append(tagDisplay).append(": ").append(newValueDisplay).append("\n");
                } else {
                    sb.append("   ✏️ ").append(tagDisplay).append(": ").append(oldValueDisplay).append(" → ").append(newValueDisplay).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("_всього змін: ").append(notification.totalChanges()).append("_");
        return sb.toString();
    }

    private String formatTagName(String tagName) {
        return switch (tagName.toUpperCase()) {
            case "TBPM" -> "bpm";
            case "TKEY" -> "key";
            case "INITIALKEY" -> "тональність";
            case "RATING" -> "рейтинг";
            case "PUBLISHER" -> "лейбл";
            case "TIT2" -> "назва";
            case "TPE1" -> "виконавець";
            case "TALB" -> "альбом";
            case "TCON" -> "жанр";
            case "TDRC", "TYER" -> "рік";
            case "COMM" -> "коментар";
            case "TCOM" -> "композитор";
            case "GRP1", "GRPG" -> "групування";
            case "TRCK" -> "номер треку";
            case "TPOS" -> "номер диску";
            case "TXXX:INITIALKEY" -> "initial key";
            case "TXXX:ENERGY" -> "energy";
            case "TXXX:COLOR" -> "color";
            case "TXXX:RATING" -> "rating (traktor)";
            case "TXXX:BPM" -> "bpm (traktor)";
            case "TXXX:KEY" -> "key (traktor)";
            default -> {
                if (tagName.startsWith("TXXX:")) yield tagName.substring(5).toLowerCase();
                yield tagName.toLowerCase();
            }
        };
    }

    private String formatTagValue(String tagName, String value) {
        if (value == null || value.isEmpty()) return "`—`";
        if ("RATING".equalsIgnoreCase(tagName)) return ratingToStars(value);
        return "`" + value + "`";
    }

    private String ratingToStars(String ratingStr) {
        try {
            int rating = Integer.parseInt(ratingStr);
            if (rating == 0) return "☆☆☆☆☆";
            if (rating <= 51) return "★☆☆☆☆";
            if (rating <= 102) return "★★☆☆☆";
            if (rating <= 153) return "★★★☆☆";
            if (rating <= 204) return "★★★★☆";
            return "★★★★★";
        } catch (Exception e) {
            return "`" + ratingStr + "`";
        }
    }
}
