package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.download.DownloadBatchAnalyzer;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchContextService;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SoulseekDownloadFlowHandler implements DownloadFlowHandler {

    private final DownloadBatchAnalyzer downloadBatchAnalyzer;
    private final SearchContextService contextService;

    @Override
    public AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, long chatId) {
        if (options.isEmpty()) {
            return new AnalysisResult(List.of(), "");
        }

        final var enrichedMetadata = contextService.getMetadataWithTracks(releaseId, chatId);
        var reports = options.stream()
                .map(opt -> new OptionReport(opt, resolveSuitabilityLevel(opt, enrichedMetadata)))
                .sorted(Comparator.comparing(OptionReport::suitability))
                .toList();

        var sortedOptions = reports.stream()
                .map(OptionReport::option)
                .toList();

        StringBuilder optionsText = buildOptionsText(sortedOptions);
        String tracklist = String.join("\n", enrichedMetadata.trackTitles());

        String aiSummary = downloadBatchAnalyzer.analyze(
                enrichedMetadata.artist(),
                enrichedMetadata.title(),
                tracklist,
                optionsText.toString()
        );

        return new AnalysisResult(reports, aiSummary);
    }

    @Override
    public BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource) {
        return BotResponse.text(formattedText);
    }

    @Override
    public String formatDownloadConfirmation(DownloadOption option) {
        return "✅ *ок, качаю:*\n%s\n📦 %d файлів, %d MB"
                .formatted(
                        option.displayName(),
                        option.files().size(),
                        option.totalSize()
                );
    }

    @Override
    public BotResponse buildAutoDownloadResponse(DownloadOption option, String releaseId) {
        return BotResponse.text("not supported");
    }

    @NotNull
    private static StringBuilder buildOptionsText(List<DownloadOption> options) {
        StringBuilder optionsText = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            optionsText.append("Option ").append(i + 1).append(":\n");
            optionsText.append(extractTracklist(options.get(i)));
            optionsText.append("\n\n");
        }
        return optionsText;
    }

    @NotNull
    private static String extractTracklist(DownloadOption option) {
        return option.files().stream()
                .map(DownloadOption.FileItem::displayName)
                .collect(Collectors.joining("\n"));
    }

    private Suitability resolveSuitabilityLevel(DownloadOption option, ReleaseMetadata expected) {
        boolean isLossless = isLossless(option);
        long diff = calculateTrackCountDiff(option, expected);

        if (isLossless && diff == 0) {
            return Suitability.PERFECT;
        } else if (isLossless && diff > 0) {
            return Suitability.GOOD;
        } else if (Math.abs(diff) <= 2 || !isLossless) {
            return Suitability.WARNING;
        } else {
            return Suitability.BAD;
        }
    }

    private boolean isLossless(DownloadOption option) {
        long audioCount = option.files().stream().filter(f -> isAudio(f.filename())).count();
        if (audioCount == 0) return false;

        long losslessCount = option.files().stream()
                .filter(f -> isHighQualityFile(f.filename()))
                .count();

        return (double) losslessCount / audioCount > 0.9;
    }

    private long calculateTrackCountDiff(DownloadOption option, ReleaseMetadata expected) {
        long audioFilesCount = option.files().stream()
                .filter(f -> isAudio(f.filename()))
                .count();
        return audioFilesCount - expected.minTracks();
    }

    private boolean isHighQualityFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".alac");
    }

    private boolean isAudio(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".flac") ||
                lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                lower.endsWith(".aac") || lower.endsWith(".ogg") ||
                lower.endsWith(".alac") || lower.endsWith(".aiff");
    }
}
