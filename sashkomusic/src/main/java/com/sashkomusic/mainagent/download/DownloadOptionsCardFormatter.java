package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.download.DownloadFlowHandler.OptionReport;
import com.sashkomusic.mainagent.download.DownloadOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DownloadOptionsCardFormatter {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "flac", "mp3", "wav", "m4a", "aac", "alac", "aiff", "ogg", "wma", "ape"
    );

    private static final int TELEGRAM_MAX_LENGTH = 4000;

    /**
     * Drops worst reports (from the tail of a suitability-sorted list) until the formatted text fits
     * within Telegram's message limit. Returns the trimmed list; original is not mutated.
     */
    public static List<OptionReport> trimToFit(List<OptionReport> reports, String aiSummary) {
        if (format(reports, aiSummary).length() <= TELEGRAM_MAX_LENGTH) {
            return reports;
        }
        List<OptionReport> trimmed = new ArrayList<>(reports);
        while (trimmed.size() > 1 && format(trimmed, aiSummary).length() > TELEGRAM_MAX_LENGTH) {
            trimmed.removeLast();
        }
        return trimmed;
    }

    public static String format(List<DownloadFlowHandler.OptionReport> reports, String aiSummary) {
        if (reports.isEmpty()) {
            return "😔 **на жаль, нич.**";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔎 знайдено %s варіантів:\n\n".formatted(reports.size()));

        int i = 1;
        for (var report : reports) {
            var option = report.option();
            var suitability = report.suitability();

            if (option.files().isEmpty()) {
                String nameWithFormat = option.displayName();
                String sourceLink = buildSourceLink(option);
                sb.append("%s **%s** (%s)%s\n\n"
                        .formatted(getIndexIcon(i), nameWithFormat, suitability.icon, sourceLink));
            } else {
                String format = detectFormat(option);
                int fileCount = option.files().size();
                String sourceLink = buildSourceLink(option);

                sb.append("%s **[%s]** • %d ф. • %d MB (%s)%s\n"
                        .formatted(getIndexIcon(i), format, fileCount, option.totalSize(), suitability.icon, sourceLink));

                option.files().stream()
                        .limit(7)
                        .forEach(f -> sb.append("   📄 `%s`\n".formatted(f.displayName())));

                if (option.files().size() > 7) {
                    sb.append("   ... _та ще %d файлів_\n".formatted(option.files().size() - 7));
                }
                sb.append("\n");
            }
            i++;
        }

        if (aiSummary != null && !aiSummary.isBlank()) {
            sb.append("💡 _%s_\n".formatted(aiSummary));
        }

        return sb.toString();
    }

    public static String formatSingle(DownloadOption option) {
        StringBuilder sb = new StringBuilder();
        String format = detectFormat(option);
        int fileCount = option.files().size();
        sb.append("1️⃣ **[%s]** • %d ф. • %d MB\n".formatted(format, fileCount, option.totalSize()));
        option.files().stream()
                .limit(7)
                .forEach(f -> sb.append("   📄 `%s`\n".formatted(f.displayName())));
        if (fileCount > 7) {
            sb.append("   ... _та ще %d файлів_\n".formatted(fileCount - 7));
        }
        return sb.toString();
    }

    private static String detectFormat(DownloadOption opt) {
        return opt.files().stream()
                .map(f -> getExtension(f.filename()))
                .filter(ext -> AUDIO_EXTENSIONS.contains(ext.toLowerCase()))
                .collect(Collectors.groupingBy(
                        String::toUpperCase,
                        Collectors.counting()
                ))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("кака");
    }

    private static String getExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1);
        }
        return "";
    }

    private static String extractFormatLabel(DownloadOption option) {
        var meta = option.technicalMetadata();
        if (meta != null) {
            String q = meta.get("qualityLabel");
            if (q != null && !q.isBlank()) return q;
            String type = meta.get("type");
            if (type != null && !type.isBlank()) return type.toUpperCase();
        }
        // fall back to last [...] in displayName
        String name = option.displayName();
        int open = name.lastIndexOf('[');
        int close = name.lastIndexOf(']');
        if (open >= 0 && close > open) return name.substring(open + 1, close);
        return name;
    }

    private static String buildSourceLink(DownloadOption option) {
        if (option.technicalMetadata() == null) return "";
        String url = switch (option.source()) {
            case QOBUZ -> option.technicalMetadata().get("albumUrl");
            case BANDCAMP, APPLE_MUSIC -> option.technicalMetadata().get("url");
            case YOUTUBE_MUSIC -> {
                String playlistId = option.technicalMetadata().get("playlistId");
                yield playlistId != null ? "https://music.youtube.com/playlist?list=" + playlistId : null;
            }
            default -> null;
        };
        return url != null ? " [🔗](" + url + ")" : "";
    }

    private static String getIndexIcon(int index) {
        return switch (index) {
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
            default -> index + ".";
        };
    }
}
