package com.sashkomusic.mainagent.process;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.libraryagent.domain.service.processFolder.ReleaseMetadataReader;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import com.sashkomusic.mainagent.process.messaging.ReprocessReleaseTaskProducer;
import com.sashkomusic.mainagent.process.messaging.dto.ReprocessReleaseTaskDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@Slf4j
public class ReprocessReleasesFlowService {

    public static final String PROCESS_ALL = "all";

    @Value("${processing.version}")
    private int processingVersion;

    private final LibraryConfig libraryConfig;
    private final ReleaseMetadataReader metadataReader;
    private final Map<SearchEngine, SearchEngineService> searchEngines;
    private final ReprocessReleaseTaskProducer taskProducer;
    private final PathMappingService pathMappingService;
    private final LibrarySearchService librarySearchService;

    public ReprocessReleasesFlowService(LibraryConfig libraryConfig,
                                        ReleaseMetadataReader metadataReader,
                                        Map<SearchEngine, SearchEngineService> searchEngines,
                                        ReprocessReleaseTaskProducer taskProducer,
                                        PathMappingService pathMappingService,
                                        LibrarySearchService librarySearchService) {
        this.libraryConfig = libraryConfig;
        this.metadataReader = metadataReader;
        this.searchEngines = searchEngines;
        this.taskProducer = taskProducer;
        this.pathMappingService = pathMappingService;
        this.librarySearchService = librarySearchService;
    }

    public ReprocessResult handle(ConversationContext ctx, String rawInput) {
        String argument = rawInput.substring("/reprocess".length()).trim();
        log.info("Processing reprocess command: conversationId={}, argument={}", ctx.conversationId(), argument);

        if (argument.isBlank()) {
            return ReprocessResult.error("Usage: /reprocess [--skip-retag] [--force] <path|all>");
        }

        ReprocessOptions options = ReprocessOptions.parse(argument);
        String path = stripQuotes(removeFlags(argument));

        if (path.isEmpty()) {
            return ReprocessResult.error("Usage: /reprocess [--skip-retag] [--force] <path|all>");
        }

        log.info("Options: skipRetag={}, force={}, path={}", options.skipRetag(), options.force(), path);

        if (PROCESS_ALL.equalsIgnoreCase(path)) {
            return reprocessAll(ctx, options);
        } else {
            return reprocessSingle(ctx, path, options);
        }
    }

    private ReprocessResult reprocessSingle(ConversationContext ctx, String pathStr, ReprocessOptions options) {
        try {
            String mappedPath = pathMappingService.mapReprocessPath(pathStr);

            // Support both absolute and relative paths
            Path inputPath = Paths.get(mappedPath);
            Path absolutePath = inputPath.isAbsolute()
                    ? inputPath
                    : Paths.get(libraryConfig.getRootPath(), mappedPath);

            if (!Files.exists(absolutePath)) {
                var errorMessage = "❌ папка не знайдена: `" + absolutePath + "`";
                log.warn(errorMessage);
                return ReprocessResult.error(errorMessage);
            }

            if (!Files.isDirectory(absolutePath)) {
                log.warn("Path is not a directory: {}", absolutePath);
                return ReprocessResult.error("Path is not a directory: " + mappedPath);
            }

            QueueResult result = queueReprocessTask(ctx, absolutePath, options);

            return switch (result) {
                case QUEUED -> {
                    log.info("Queued reprocess task for: {} (options={})", pathStr, options);
                    yield ReprocessResult.success("🚀 опрацьовую...", 1);
                }
                case SKIPPED -> {
                    log.info("Skipped (already up-to-date): {}", pathStr);
                    yield ReprocessResult.success("✅ вже востатна версія", 0);
                }
                case FAILED -> {
                    log.warn("Failed to queue: {}", pathStr);
                    yield ReprocessResult.error("❌ не вдалося queue task");
                }
            };

        } catch (Exception ex) {
            log.error("Failed to reprocess {}: {}", pathStr, ex.getMessage(), ex);
            return ReprocessResult.error("Failed to reprocess: " + ex.getMessage());
        }
    }

    private ReleaseMetadata getMetadata(ReprocessOptions options, ReleaseMetadataFile metadataFile) {
        if (options.skipRetag()) {
            return createMetadataFromFile(metadataFile);
        }
        return searchEngines.get(metadataFile.source()).getReleaseMetadata(metadataFile);
    }

    private enum QueueResult {
        QUEUED,
        SKIPPED,
        FAILED
    }

    private QueueResult queueReprocessTask(ConversationContext ctx, Path releaseDir, ReprocessOptions options) {
        try {
            var metadataOpt = metadataReader.readMetadata(releaseDir);
            if (metadataOpt.isEmpty()) {
                log.warn("No metadata file found in: {}", releaseDir);
                return QueueResult.FAILED;
            }

            ReleaseMetadataFile metadataFile = metadataOpt.get();

            if (!options.force() && metadataFile.metadataVersion() >= processingVersion) {
                log.debug("Skipping {} - already at version {} (current: {})",
                        releaseDir.getFileName(), metadataFile.metadataVersion(), processingVersion);
                return QueueResult.SKIPPED;
            }

            if (options.force()) {
                log.info("Force reprocessing {} (current version: {})",
                        releaseDir.getFileName(), metadataFile.metadataVersion());
            }

            log.debug("Processing release: {} - {} (version: {} -> {})",
                    metadataFile.artist(), metadataFile.title(),
                    metadataFile.metadataVersion(), processingVersion);

            ReleaseMetadata metadata = getMetadata(options, metadataFile);

            ReprocessReleaseTaskDto task = new ReprocessReleaseTaskDto(
                    ctx.conversationId(),
                    releaseDir.toString(),
                    metadata,
                    processingVersion,
                    options
            );
            taskProducer.send(task);

            log.debug("Queued reprocess task for: {} (options={})", releaseDir, options);
            return QueueResult.QUEUED;

        } catch (Exception ex) {
            log.error("Failed to queue reprocess for {}: {}", releaseDir, ex.getMessage());
            return QueueResult.FAILED;
        }
    }

    private ReprocessResult reprocessAll(ConversationContext ctx, ReprocessOptions options) {
        try {
            Path rootPath = Paths.get(libraryConfig.getRootPath());

            if (!Files.exists(rootPath)) {
                log.error("Library root path does not exist: {}", rootPath);
                return ReprocessResult.error("Library root path not found");
            }

            log.info("Scanning library for releases to reprocess: {} (options={})", rootPath, options);

            int indexed = librarySearchService.reindexAll();
            log.info("Pre-indexed {} existing releases in search index", indexed);

            List<Path> releaseDirs = findAllReleasesWithMetadata(rootPath);

            if (releaseDirs.isEmpty()) {
                log.info("No releases with metadata found");
                return ReprocessResult.success("No releases found to reprocess", 0);
            }

            log.info("Found {} releases to reprocess", releaseDirs.size());

            int queuedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;

            for (Path releaseDir : releaseDirs) {
                QueueResult result = queueReprocessTask(ctx, releaseDir, options);
                switch (result) {
                    case QUEUED -> queuedCount++;
                    case SKIPPED -> skippedCount++;
                    case FAILED -> errorCount++;
                }
            }

            String message = String.format("🚀 %d queued", queuedCount);
            if (skippedCount > 0) {
                message += String.format(", ✅ %d вже востатна версія", skippedCount);
            }
            if (errorCount > 0) {
                message += String.format(", ❌ %d errors", errorCount);
            }

            return ReprocessResult.success(message, queuedCount);

        } catch (Exception ex) {
            log.error("Failed to scan library: {}", ex.getMessage(), ex);
            return ReprocessResult.error("Failed to scan library: " + ex.getMessage());
        }
    }

    private List<Path> findAllReleasesWithMetadata(Path rootPath) throws IOException {
        List<Path> releaseDirs = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths
                    .filter(Files::isDirectory)
                    .filter(metadataReader::hasMetadataFile)
                    .forEach(releaseDirs::add);
        }

        return releaseDirs;
    }

    private ReleaseMetadata createMetadataFromFile(ReleaseMetadataFile metadataFile) {
        return new ReleaseMetadata(
                metadataFile.sourceId(),
                metadataFile.masterId(),
                metadataFile.source(),
                metadataFile.artist(),
                metadataFile.title(),
                0,
                metadataFile.year() != null ? List.of(String.valueOf(metadataFile.year())) : List.of(),
                metadataFile.types() != null ? metadataFile.types() : List.of(),
                metadataFile.trackCount(),
                metadataFile.trackCount(),
                1,
                List.of(), // tracks - will be read from audio files
                "", // coverUrl - not needed for reprocessing
                metadataFile.tags() != null ? metadataFile.tags() : List.of(),
                metadataFile.label() != null ? metadataFile.label() : ""
        );
    }

    public record ReprocessOptions(
            boolean skipRetag,
            boolean force
    ) {
        public static ReprocessOptions parse(String argument) {
            boolean skipRetag = argument.contains("--skip-retag");
            boolean force = argument.contains("--force");
            return new ReprocessOptions(skipRetag, force);
        }

        public static ReprocessOptions defaults() {
            return new ReprocessOptions(false, false);
        }
    }

    public record ReprocessResult(
            boolean success,
            String message,
            int count
    ) {
        public static ReprocessResult success(String message, int count) {
            return new ReprocessResult(true, message, count);
        }

        public static ReprocessResult error(String message) {
            return new ReprocessResult(false, message, 0);
        }
    }

    private static String removeFlags(String argument) {
        return argument
                .replace("--skip-retag", "")
                .replace("--force", "")
                .trim();
    }

    private static String stripQuotes(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        String trimmed = path.trim();

        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }
}
