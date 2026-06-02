package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;

import java.util.List;

public interface DownloadFlowHandler {

    AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, String conversationId);

    BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource);

    String formatDownloadConfirmation(DownloadOption option);

    record OptionReport(
            DownloadOption option,
            Suitability suitability
    ) {
    }

    record AnalysisResult(
            List<OptionReport> reports,
            String aiSummary
    ) {
    }

    enum Suitability {
        PERFECT("💎"),
        GOOD("🟢"),
        WARNING("🟡"),
        BAD("🔴");

        public final String icon;

        Suitability(String icon) {
            this.icon = icon;
        }
    }
}
