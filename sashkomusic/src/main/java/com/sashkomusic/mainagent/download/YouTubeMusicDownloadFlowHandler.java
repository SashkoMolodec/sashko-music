package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

@Component
public class YouTubeMusicDownloadFlowHandler implements DownloadFlowHandler {

    @Override
    public AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, String conversationId) {
        if (options.isEmpty()) {
            return new AnalysisResult(List.of(), "");
        }

        var reports = options.stream()
                .map(opt -> new OptionReport(opt, Suitability.GOOD))
                .toList();

        return new AnalysisResult(reports, "");
    }

    @Override
    public BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource) {
        var buttons = new LinkedHashMap<String, String>();
        buttons.put("🎵", "SEARCH_ALT:" + releaseId + ":QOBUZ");
        buttons.put("🍏", "SEARCH_ALT:" + releaseId + ":APPLE_MUSIC");
        buttons.put("📼", "SEARCH_ALT:" + releaseId + ":BANDCAMP");
        buttons.put("⛏️", "SEARCH_ALT:" + releaseId + ":SOULSEEK");
        return BotResponse.withButtons(formattedText, buttons);
    }

    @Override
    public String formatDownloadConfirmation(DownloadOption option) {
        return "✅ *ок, качаю з youtube music:*\n%s".formatted(option.displayName());
    }

}
