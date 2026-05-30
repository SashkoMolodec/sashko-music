package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pure formatter — turns grouped search results into a Telegram options card
 * with numbered emoji bullets per source.
 */
@Component
public class ProcessOptionsFormatter {

    public BotResponse format(ProcessFolderSearcher.SearchResults results) {
        StringBuilder message = new StringBuilder();
        int optionIndex = 1;

        if (!results.mbResults().isEmpty()) {
            message.append("🎵 _musicbrainz:_\n");
            optionIndex = appendResults(message, results.mbResults(), optionIndex);
            message.append("\n");
        }
        if (!results.discogsResults().isEmpty()) {
            message.append("💿 _discogs:_\n");
            optionIndex = appendResults(message, results.discogsResults(), optionIndex);
            message.append("\n");
        }
        if (!results.bandcampResults().isEmpty()) {
            message.append("📼 _bandcamp:_\n");
            appendResults(message, results.bandcampResults(), optionIndex);
            message.append("\n");
        }
        return BotResponse.text(message.toString());
    }

    private int appendResults(StringBuilder message, List<ReleaseMetadata> results, int startIndex) {
        int index = startIndex;
        for (ReleaseMetadata result : results) {
            message.append(toEmojiNumber(index))
                    .append(" **")
                    .append(result.artist().toLowerCase())
                    .append(" - ")
                    .append(result.title().toLowerCase())
                    .append("**");

            String yearsDisplay = result.getYearsDisplay();
            if (!yearsDisplay.isEmpty() && !yearsDisplay.equals("N/A")) {
                message.append(" • ").append(yearsDisplay);
            }

            String trackCountDisplay = result.getTrackCountDisplay();
            if (!trackCountDisplay.isEmpty()) {
                message.append(" • ").append(trackCountDisplay).append(" тр.");
            }

            if (result.tags() != null && !result.tags().isEmpty()) {
                message.append(" • ").append(result.getTagsDisplay().toLowerCase());
            }

            message.append("\n");
            index++;
        }
        return index;
    }

    private String toEmojiNumber(int number) {
        return switch (number) {
            case 1 -> "1️⃣";
            case 2 -> "2️⃣";
            case 3 -> "3️⃣";
            case 4 -> "4️⃣";
            case 5 -> "5️⃣";
            case 6 -> "6️⃣";
            case 7 -> "7️⃣";
            case 8 -> "8️⃣";
            case 9 -> "9️⃣";
            case 10 -> "🔟";
            default -> number + ".";
        };
    }
}
