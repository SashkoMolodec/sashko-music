package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure formatter — turns grouped search results into a Telegram options card
 * with numbered emoji bullets per source.
 */
@Component
@RequiredArgsConstructor
public class ProcessOptionsFormatter {

    private final Map<SearchEngine, SearchEngineService> searchEngines;

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

        int total = results.allResults().size();
        List<List<BotResponse.ButtonDto>> rows = buildSelectionButtons(total);
        return BotResponse.withMultiRowButtons(message.toString(), rows);
    }

    private List<List<BotResponse.ButtonDto>> buildSelectionButtons(int count) {
        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();
        List<BotResponse.ButtonDto> row = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            row.add(new BotResponse.ButtonDto(toEmojiNumber(i + 1), "PROC_SEL:" + i));
            if (row.size() == 5) {
                rows.add(List.copyOf(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(List.copyOf(row));
        rows.add(List.of(new BotResponse.ButtonDto("❌ скасувати", "PROC_SEL:cancel")));
        return rows;
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

            String releaseUrl = buildReleaseUrl(result);
            if (releaseUrl != null) {
                message.append(" [🔗](").append(releaseUrl).append(")");
            }

            message.append("\n");
            index++;
        }
        return index;
    }

    private String buildReleaseUrl(ReleaseMetadata result) {
        if (result.source() == null) return null;
        SearchEngineService service = searchEngines.get(result.source());
        if (service == null) return null;
        try {
            return service.buildReleaseUrl(result);
        } catch (Exception e) {
            return null;
        }
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
