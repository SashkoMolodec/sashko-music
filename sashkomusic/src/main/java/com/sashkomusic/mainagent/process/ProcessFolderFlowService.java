package com.sashkomusic.mainagent.process;

import com.sashkomusic.libraryagent.domain.service.processFolder.FolderAudioScanner;
import com.sashkomusic.libraryagent.domain.service.processFolder.ReleaseIdentifierService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.DownloadContextHolder;
import com.sashkomusic.mainagent.process.messaging.ProcessLibraryTaskProducer;
import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;
import com.sashkomusic.mainagent.search.MetadataUrlFetcher;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.Language;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sashkomusic.mainagent.search.SearchEngine.BANDCAMP;
import static com.sashkomusic.mainagent.search.SearchEngine.DISCOGS;
import static com.sashkomusic.mainagent.search.SearchEngine.MUSICBRAINZ;

/**
 * Orchestrates the {@code /process <folder>} Telegram dialogue:
 * scan folder → identify release → search 3 sources → present options →
 * on user pick, dispatch a {@code ProcessLibraryTaskEvent} to libraryagent.
 * <p>
 * Heavy lifting is delegated:
 *   - {@link FolderAudioScanner}     — filesystem + folder name cleaning
 *   - {@link ProcessFolderSearcher}  — multi-source metadata search
 *   - {@link ProcessOptionsFormatter}— Telegram options card
 *   - {@link ReleaseIdentifierService} (libraryagent) — folder/tags → search request
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessFolderFlowService {

    private final FolderAudioScanner audioScanner;
    private final ProcessFolderSearcher searcher;
    private final ProcessOptionsFormatter optionsFormatter;
    private final MetadataSuggester metadataSuggester;
    private final ReleaseIdentifierService identifierService;
    private final SearchContextService searchContextService;
    private final MetadataUrlFetcher metadataUrlFetcher;
    private final ProcessFolderContextHolder contextHolder;
    private final ProcessLibraryTaskProducer libraryTaskProducer;
    private final PathMappingService pathMappingService;
    private final DownloadContextHolder downloadContextHolder;

    public List<BotResponse> handleProcessCommand(ConversationContext ctx, String rawInput) {
        return process(ctx, audioScanner.stripProcessPrefix(rawInput), "");
    }

    public List<BotResponse> process(ConversationContext ctx, String folderName) {
        return process(ctx, folderName, "");
    }

    public List<BotResponse> process(ConversationContext ctx, String folderName, String additionalContext) {
        return process(ctx, folderName, additionalContext, null);
    }

    /**
     * @param knownReleaseId release the user already picked to trigger this download (via {@code DL:}) —
     *                       looked up in {@link SearchContextService} and, if still resolvable, surfaced
     *                       as option 1 instead of forcing the user to re-search/re-paste it.
     */
    public List<BotResponse> process(ConversationContext ctx, String folderName, String additionalContext, String knownReleaseId) {
        try {
            String processPath = pathMappingService.mapProcessPath(folderName);
            FolderAudioScanner.ResolvedFolder folder = audioScanner.resolve(processPath);

            if (!folder.isDirectory()) {
                Optional<Path> closest = audioScanner.findClosestFolder(folderName);
                if (closest.isEmpty()) {
                    return List.of(BotResponse.text("❌ папка не знайдена: `" + folder.path() + "`"));
                }
                log.info("Direct path not found, using closest match: {}", closest.get());
                folder = audioScanner.resolve(closest.get().toString());
            }

            List<String> audioFiles = audioScanner.listAudioFiles(folder.path());
            if (audioFiles.isEmpty()) {
                return List.of(BotResponse.text("❌ В папці немає аудіо-файлів"));
            }

            log.info("Processing folder: {}, found {} audio files", folder.name(), audioFiles.size());

            MetadataSearchRequest searchRequest = buildSearchRequest(folder.name(), audioFiles, additionalContext);

            ReleaseMetadata knownRelease = knownReleaseId != null
                    ? searchContextService.getReleaseMetadata(knownReleaseId, ctx.conversationId())
                    : null;

            List<BotResponse> invalid = validateSearchRequest(searchRequest, folder.name());
            if (invalid != null && knownRelease == null) return invalid;

            ProcessFolderSearcher.SearchResults results = invalid == null
                    ? searcher.searchAll(searchRequest)
                    : new ProcessFolderSearcher.SearchResults(List.of(), List.of(), List.of(), List.of());

            if (knownRelease != null) {
                results = results.withKnownRelease(knownRelease);
                log.info("Pre-filled known release as option 1: {} - {}", knownRelease.artist(), knownRelease.title());
            }

            BotResponse header = BotResponse.text("📄 %d файлів, знайдено метадані:".formatted(audioFiles.size()));

            if (results.isEmpty()) {
                contextHolder.save(ctx.conversationId(), folder.path().toString(), audioFiles, List.of());
                return List.of(header, BotResponse.text("""
                        ❌ нема шось метаданих

                        уточни пошук:
                        • +Виконавець - Альбом
                        • +рік або жанр
                        • або скинь посилання на реліз"""));
            }

            saveSearchContext(ctx, folder.name(), searchRequest, results, folder.path(), audioFiles);
            downloadContextHolder.clearSession(ctx.conversationId());

            var responses = new java.util.ArrayList<BotResponse>();
            responses.add(header);
            responses.add(optionsFormatter.format(results));
            try {
                String suggestion = metadataSuggester.suggest(
                        buildSuggesterInput(folder.name(), audioFiles, results.allResults()));
                if (suggestion != null && !suggestion.isBlank()) {
                    responses.add(BotResponse.aiText(suggestion.toLowerCase()));
                }
            } catch (Exception e) {
                log.warn("MetadataSuggester failed: {}", e.getMessage());
            }
            return responses;

        } catch (Exception e) {
            log.error("Error processing folder: {}", e.getMessage(), e);
            return List.of(BotResponse.text("❌ помилка обробки папки: " + e.getMessage()));
        }
    }

    public List<BotResponse> handleMetadataSelection(ConversationContext ctx, String rawInput) {
        String trimmed = rawInput.trim();

        if (metadataUrlFetcher.isUrl(trimmed)) {
            return handleUrlMetadataSelection(ctx, trimmed);
        }

        if (trimmed.startsWith("+")) {
            return handleAdditionalContext(ctx, trimmed.substring(1).trim());
        }

        if (trimmed.equals("-")) {
            contextHolder.clear(ctx.conversationId());
            return List.of(BotResponse.text("❌ скасовано"));
        }

        return List.of(BotResponse.text("обери варіант кнопкою вище або скинь посилання на реліз"));
    }

    public List<BotResponse> handleMetadataSelectionByIndex(ConversationContext ctx, String data) {
        String payload = data.substring("PROC_SEL:".length());
        if ("cancel".equals(payload)) {
            contextHolder.clear(ctx.conversationId());
            return List.of(BotResponse.text("❌ скасовано"));
        }

        int index;
        try {
            index = Integer.parseInt(payload);
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("❌ невідома команда"));
        }

        var state = contextHolder.get(ctx.conversationId());
        if (state.isEmpty()) {
            return List.of(BotResponse.text("❌ сесія закінчилась. спробуй /process ще раз"));
        }

        String releaseId = contextHolder.getReleaseIdByOption(ctx.conversationId(), index);
        if (releaseId == null) {
            return List.of(BotResponse.text("❌ невірний варіант"));
        }

        ReleaseMetadata metadata = searchContextService.getMetadataWithTracks(releaseId, ctx.conversationId());
        if (metadata == null) {
            return List.of(BotResponse.text("❌ метадані не знайдено"));
        }

        var folderState = state.get();
        libraryTaskProducer.send(ProcessLibraryTaskDto.of(
                ctx.conversationId(), folderState.directoryPath(), folderState.audioFiles(), metadata));
        contextHolder.clear(ctx.conversationId());

        log.info("Sent library processing task: conversationId={}, directory={}",
                ctx.conversationId(), folderState.directoryPath());
        return List.of(BotResponse.text("🚀 опрацьовую..."));
    }

    public boolean hasActiveContext(ConversationContext ctx) {
        return contextHolder.hasActiveContext(ctx.conversationId());
    }

    private List<BotResponse> handleUrlMetadataSelection(ConversationContext ctx, String url) {
        var stateOpt = contextHolder.get(ctx.conversationId());
        if (stateOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ сесія закінчилась. спробуй /process ще раз"));
        }

        var metadata = metadataUrlFetcher.fetch(url);
        if (metadata.isEmpty()) {
            return List.of(BotResponse.text(
                    "❌ не вдалося отримати метадані. підтримуються:\n" +
                    "• discogs.com/release/…\n" +
                    "• musicbrainz.org/release/…\n" +
                    "• artist.bandcamp.com/album/…"));
        }

        var release = metadata.get();
        var state = stateOpt.get();
        libraryTaskProducer.send(ProcessLibraryTaskDto.of(
                ctx.conversationId(), state.directoryPath(), state.audioFiles(), release));
        contextHolder.clear(ctx.conversationId());

        log.info("Sent library processing task from URL: conversationId={}, directory={}, url={}",
                ctx.conversationId(), state.directoryPath(), url);
        return List.of(BotResponse.text("✅ " + buildReleaseSummary(release) + "\n🚀 опрацьовую..."));
    }

    private static String buildReleaseSummary(ReleaseMetadata release) {
        var sb = new StringBuilder();
        if (release.artist() != null && !release.artist().isBlank()) {
            sb.append(release.artist()).append(" — ");
        }
        sb.append(release.title() != null ? release.title() : "?");
        if (release.years() != null && !release.years().isEmpty()) {
            sb.append(" (").append(release.years().getFirst()).append(")");
        }
        if (release.tracks() != null && !release.tracks().isEmpty()) {
            sb.append(" · ").append(release.tracks().size()).append(" тр.");
        }
        return sb.toString();
    }

    private List<BotResponse> handleAdditionalContext(ConversationContext ctx, String additionalContext) {
        var stateOpt = contextHolder.get(ctx.conversationId());
        if (stateOpt.isEmpty()) {
            return List.of(BotResponse.text("❌ сесія закінчилась. спробуй /process ще раз"));
        }
        downloadContextHolder.clearSession(ctx.conversationId());
        return process(ctx, stateOpt.get().directoryPath(), additionalContext);
    }

    private MetadataSearchRequest buildSearchRequest(String folderName, List<String> audioFiles, String additionalContext) {
        var fromTags = identifierService.identifyFromAudioFile(audioFiles.getFirst());

        if (fromTags != null) {
            String artist = extractMostCommonArtist(audioFiles);
            log.info("Using release info from audio file tags (majority artist='{}')", artist);
            return MetadataSearchRequest.create(
                    artist, withContext(fromTags.album(), additionalContext),
                    null, null, null, null, null, null, null, null, null, Language.EN);
        }

        log.info("No tags in audio file, parsing folder name");
        return identifierService.identifyFromFolderName(withContext(folderName, additionalContext));
    }

    private String extractMostCommonArtist(List<String> audioFiles) {
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (String file : audioFiles) {
            var info = identifierService.identifyFromAudioFile(file);
            if (info != null && info.artist() != null && !info.artist().isBlank()) {
                counts.merge(info.artist().trim(), 1, Integer::sum);
                total++;
            }
        }
        if (counts.isEmpty()) return null;

        var top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        double percentage = (double) top.getValue() / total * 100;
        log.info("Most common artist: '{}' ({} of {} tracks, {})",
                top.getKey(), top.getValue(), total, "%.1f%%".formatted(percentage));
        return percentage >= 50.0 ? top.getKey() : null;
    }

    private List<BotResponse> validateSearchRequest(MetadataSearchRequest searchRequest, String folderName) {
        if (searchRequest == null || searchRequest.release().isEmpty()) {
            return List.of(BotResponse.text("""
                    ❌ не вдалося розпізнати назву релізу з папки: `%s`

                    допиши контексту:
                    • /process Artist - Album
                    • /process Artist - Album касета 1990 україна
                    """.formatted(folderName)));
        }
        return null;
    }

    private void saveSearchContext(ConversationContext ctx, String folderName, MetadataSearchRequest searchRequest,
                                   ProcessFolderSearcher.SearchResults results, Path folderPath, List<String> audioFiles) {
        List<ReleaseMetadata> all = results.allResults();

        SearchEngine primarySource = !results.mbResults().isEmpty() ? MUSICBRAINZ
                : !results.discogsResults().isEmpty() ? DISCOGS : BANDCAMP;

        searchContextService.saveSearchContext(ctx.conversationId(), primarySource, folderName, searchRequest, all);

        List<String> releaseIds = all.stream()
                .map(ReleaseMetadata::id)
                .toList();

        contextHolder.save(ctx.conversationId(), folderPath.toString(), audioFiles, releaseIds);
    }

    private static String withContext(String value, String additionalContext) {
        return value + " " + additionalContext;
    }

    private static String buildSuggesterInput(String folderName, List<String> audioFiles,
                                              List<ReleaseMetadata> options) {
        var sb = new StringBuilder();
        sb.append("Папка: ").append(folderName).append("\n");
        sb.append("Файли: ").append(String.join(", ",
                audioFiles.stream().map(f -> Path.of(f).getFileName().toString()).toList())).append("\n\n");
        sb.append("Варіанти:\n");
        for (int i = 0; i < options.size(); i++) {
            var r = options.get(i);
            sb.append(i + 1).append(". ");
            if (r.artist() != null && !r.artist().isBlank()) sb.append(r.artist()).append(" - ");
            sb.append(r.title() != null ? r.title() : "?");
            if (r.years() != null && !r.years().isEmpty()) sb.append(" • ").append(r.getYearsDisplay());
            String trackDisplay = r.getTrackCountDisplay();
            if (!trackDisplay.isEmpty()) sb.append(" • ").append(trackDisplay).append(" тр.");
            if (r.tags() != null && !r.tags().isEmpty()) {
                sb.append(" • ").append(r.getTagsDisplay());
            }
            sb.append("\n");
        }
        return sb.toString().strip();
    }
}
