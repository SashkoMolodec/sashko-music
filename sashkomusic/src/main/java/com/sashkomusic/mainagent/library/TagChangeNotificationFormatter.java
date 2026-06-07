package com.sashkomusic.mainagent.library;

import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;
import org.springframework.stereotype.Component;

/** Pure formatter — turns a TagChangesNotificationDto into a Telegram-ready Markdown message. */
@Component
public class TagChangeNotificationFormatter {

    public String format(TagChangesNotificationDto notification) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎵 **оновлено теги треків**\n\n");
        for (TagChangesNotificationDto.TrackChanges track : notification.tracks()) {
            sb.append("📀 _").append(track.artistName().toLowerCase())
                    .append(" — ").append(track.trackTitle().toLowerCase()).append("_\n");
            for (TagChangesNotificationDto.TagChangeInfo change : track.changes()) {
                String tag = formatTagName(change.tagName());
                String oldVal = formatTagValue(change.tagName(), change.oldValue());
                String newVal = formatTagValue(change.tagName(), change.newValue());
                if (change.isNew()) {
                    sb.append("   ➕ ").append(tag).append(": ").append(newVal).append("\n");
                } else {
                    sb.append("   ✏️ ").append(tag).append(": ").append(oldVal).append(" → ").append(newVal).append("\n");
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
