package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.download.DownloadBatchAnalyzer;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SoulseekDownloadFlowHandler implements DownloadFlowHandler {

    private final DownloadBatchAnalyzer downloadBatchAnalyzer;
    private final SearchContextService contextService;

    @Override
    public AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, String conversationId) {
        if (options.isEmpty()) {
            return new AnalysisResult(List.of(), "");
        }

        final var enrichedMetadata = contextService.getMetadataWithTracks(releaseId, conversationId);
        int expectedTrackCount = enrichedMetadata != null ? resolveExpectedTrackCount(enrichedMetadata) : 0;
        log.info("Expected track count from metadata: {}", expectedTrackCount);
        var allReports = options.stream()
                .map(opt -> new OptionReport(opt, resolveSuitabilityLevel(opt, enrichedMetadata)))
                .sorted(Comparator.comparing(OptionReport::suitability)
                        .thenComparingInt(r -> {
                            if (expectedTrackCount == 0) return 0;
                            long audio = r.option().files().stream().filter(f -> isAudio(f.filename())).count();
                            return (int) Math.abs(audio - expectedTrackCount);
                        }))
                .toList();

        var firstPage = allReports.stream().limit(DownloadContextHolder.PAGE_SIZE).toList();

        if (enrichedMetadata == null) {
            return new AnalysisResult(allReports, "");
        }

        StringBuilder optionsText = buildOptionsText(firstPage.stream().map(OptionReport::option).toList());
        String tracklist = String.join("\n", enrichedMetadata.trackTitles());

        String aiSummary = downloadBatchAnalyzer.analyze(
                enrichedMetadata.artist(),
                enrichedMetadata.title(),
                tracklist,
                optionsText.toString()
        );

        return new AnalysisResult(allReports, aiSummary);
    }

    @Override
    public BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource) {
        return BotResponse.withMultiRowButtons(formattedText, List.of(
                List.of(
                        new BotResponse.ButtonDto("🔍", "SLSK_CUSTOM:" + releaseId),
                        new BotResponse.ButtonDto("⛏️", "SEARCH_ALT:" + releaseId + ":SOULSEEK"),
                        new BotResponse.ButtonDto("❌", "DLOPT:cancel")
                )
        ));
    }

    @Override
    public boolean appendDefaultCancelRow() {
        return false;
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
        int expectedTrackCount = expected != null ? resolveExpectedTrackCount(expected) : 0;
        if (expectedTrackCount == 0) {
            return Suitability.WARNING;
        }

        boolean isLossless = isLossless(option);
        long audioFilesCount = option.files().stream().filter(f -> isAudio(f.filename())).count();
        long diff = audioFilesCount - expectedTrackCount;

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

    private int resolveExpectedTrackCount(ReleaseMetadata expected) {
        if (expected.tracks() != null && !expected.tracks().isEmpty()) {
            return expected.tracks().size();
        }
        return expected.minTracks();
    }

    private boolean isLossless(DownloadOption option) {
        long audioCount = option.files().stream().filter(f -> isAudio(f.filename())).count();
        if (audioCount == 0) return false;

        long losslessCount = option.files().stream()
                .filter(f -> isHighQualityFile(f.filename()))
                .count();

        return (double) losslessCount / audioCount > 0.9;
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
