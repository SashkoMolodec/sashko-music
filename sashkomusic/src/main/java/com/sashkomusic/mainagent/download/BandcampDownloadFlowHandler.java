package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

@Component
public class BandcampDownloadFlowHandler implements DownloadFlowHandler {

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
        return BotResponse.text(formattedText);
    }

    @Override
    public String formatDownloadConfirmation(DownloadOption option) {
        return "✅ *ок, качаю з bandcamp:*\n%s".formatted(option.displayName());
    }

}
