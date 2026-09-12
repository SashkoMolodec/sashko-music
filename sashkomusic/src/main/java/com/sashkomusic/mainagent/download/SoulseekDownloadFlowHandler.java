package com.sashkomusic.mainagent.download;

import com.sashkomusic.mainagent.download.DownloadBatchAnalyzer;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;
import com.sashkomusic.shared.model.ReleaseMetadata;
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
                .map(opt -> buildReport(opt, enrichedMetadata))
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

    /**
     * Cosmetic-only classification (sort order + summary text shown to the user before they pick an
     * option) — it never blocks a download. A folder with more files than the release has tracks (e.g. a
     * bonus/maxi-single, or — per a real incident — a duplicate track kept in two formats) is a choice the
     * user might deliberately make, so we surface it as a warning rather than filtering it out. The actual
     * hard gate against duplicate-inflated file counts lives in
     * {@code libraryagent.domain.service.processFolder.FileValidator}, which runs right before the files
     * are persisted into the library and has no further human review step after it.
     */
    private OptionReport buildReport(DownloadOption option, ReleaseMetadata expected) {
        int expectedTrackCount = expected != null ? resolveExpectedTrackCount(expected) : 0;
        if (expectedTrackCount == 0) {
            return new OptionReport(option, Suitability.WARNING);
        }

        boolean isLossless = isLossless(option);
        long audioFilesCount = option.files().stream().filter(f -> isAudio(f.filename())).count();
        long diff = audioFilesCount - expectedTrackCount;

        if (diff == 0) {
            return new OptionReport(option, isLossless ? Suitability.PERFECT : Suitability.WARNING);
        }

        // Any mismatch — too many OR too few files versus the expected track count — gets at least a
        // WARNING with an explicit message, never a silent GOOD/PERFECT. Previously an extra file on an
        // otherwise-lossless option was scored GOOD, which is exactly what hid the duplicate-track bug
        // from the user in the incident that motivated this check.
        Suitability suitability = (Math.abs(diff) <= 2 || !isLossless) ? Suitability.WARNING : Suitability.BAD;
        String warning = diff > 0
                ? "⚠️ цей варіант має %d файлів, більше ніж очікується %d треків — можливий дубль треку в іншому форматі"
                        .formatted(audioFilesCount, expectedTrackCount)
                : "⚠️ цей варіант має %d файлів, менше ніж очікується %d треків — можливо не всі треки"
                        .formatted(audioFilesCount, expectedTrackCount);

        return new OptionReport(option, suitability, warning);
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
