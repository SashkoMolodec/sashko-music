package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QobuzDownloadFlowHandler implements DownloadFlowHandler {

    // Quality priority mapping (higher = better)
    private static final Map<String, Integer> QUALITY_PRIORITY = Map.of(
            "27", 4,  // 24-Bit/192 kHz
            "7", 3,   // 24-Bit/96 kHz
            "6", 2,   // 16-Bit/44.1 kHz
            "5", 1    // MP3 320
    );

    @Override
    public AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, long chatId) {
        if (options.isEmpty()) {
            return new AnalysisResult(List.of(), "");
        }

        var reports = options.stream()
                .map(opt -> new OptionReport(opt, Suitability.PERFECT))
                .sorted(Comparator.comparingInt(this::getQualityPriority).reversed())
                .toList();

        return new AnalysisResult(reports, "");
    }

    @Override
    public BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource) {
        var buttons = new LinkedHashMap<String, String>();
        buttons.put("🍏", "SEARCH_ALT:" + releaseId + ":APPLE_MUSIC");
        buttons.put("📼", "SEARCH_ALT:" + releaseId + ":BANDCAMP");
        buttons.put("⛏️", "SEARCH_ALT:" + releaseId + ":SOULSEEK");
        return BotResponse.withButtons(formattedText, buttons);
    }

    @Override
    public String formatDownloadConfirmation(DownloadOption option) {
        return "✅ *ок, качаю:*\n%s".formatted(option.displayName());
    }

    @Override
    public BotResponse buildAutoDownloadResponse(DownloadOption option, String releaseId) {
        String message = "✅ **знайшов то шо треба, для душі, качаю:**\n`%s`".formatted(option.displayName());

        var buttons = new LinkedHashMap<String, String>();
        buttons.put("❌", "CANCEL_DL:" + releaseId);
        buttons.put("🍏", "SEARCH_ALT:" + releaseId + ":APPLE_MUSIC");
        buttons.put("📼", "SEARCH_ALT:" + releaseId + ":BANDCAMP");
        buttons.put("⛏️", "SEARCH_ALT:" + releaseId + ":SOULSEEK");

        return BotResponse.withButtons(message, buttons);
    }

    private int getQualityPriority(OptionReport report) {
        String quality = report.option().technicalMetadata().get("quality");
        return QUALITY_PRIORITY.getOrDefault(quality, 0);
    }
}
