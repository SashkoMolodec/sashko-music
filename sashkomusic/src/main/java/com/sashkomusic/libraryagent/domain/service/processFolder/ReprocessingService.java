package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService.ReprocessOptions;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import com.sashkomusic.libraryagent.domain.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReprocessingService {

    private final AudioTagger audioTagger;
    private final TrackMatcher trackMatcher;
    private final CoverArtService coverArtService;
    private final ReleaseMetadataWriter metadataWriter;
    private final ReleaseService releaseService;

    @Transactional
    public ReprocessResult reprocess(String directoryPath, ReleaseMetadata metadata, int newVersion,
                                      ReprocessOptions options) {
        
        log.info("Starting reprocessing for: {} (options={})", directoryPath, options);
        log.info("Metadata: {} - {} (version {})", metadata.artist(), metadata.title(), newVersion);

        try {
            Path releaseDir = Paths.get(directoryPath);

            if (!Files.exists(releaseDir) || !Files.isDirectory(releaseDir)) {
                log.error("Directory not found or not a directory: {}", directoryPath);
                return ReprocessResult.failure("Directory not found: " + directoryPath);
            }

            List<Path> audioFiles = findAudioFiles(releaseDir);

            if (audioFiles.isEmpty()) {
                log.warn("No audio files found in: {}", directoryPath);
                return ReprocessResult.failure("No audio files found");
            }

            log.info("Found {} audio files to reprocess", audioFiles.size());

            int successCount = 0;
            int errorCount = 0;

            if (options.skipRetag()) {
                log.info("Skipping audio file re-tagging (--skip-retag flag set)");
                successCount = audioFiles.size();
            } else {
                byte[] coverArt = coverArtService.getCoverArt(metadata, directoryPath);

                // Match files using tags (if valid) or filename-based matching
                Map<String, TrackMatch> matchMap = trackMatcher.match(audioFiles, metadata);

                // Re-tag all audio files
                for (Path filePath : audioFiles) {
                    try {
                        TrackMatch match = matchMap.get(filePath.toString());
                        if (match == null) {
                            log.error("No match found for {} - this should not happen!", filePath.getFileName());
                            errorCount++;
                            continue;
                        }

                        log.info("Tagging file {} with: trackNumber={}, title='{}', artist='{}'",
                                filePath.getFileName(), match.trackNumber(), match.trackTitle(), match.artist());

                        audioTagger.tagFile(filePath, metadata, match, coverArt);
                        successCount++;
                        log.debug("Successfully retagged: {}", filePath.getFileName());

                    } catch (Exception ex) {
                        log.error("Failed to retag {}: {}", filePath.getFileName(), ex.getMessage());
                        errorCount++;
                    }
                }

                if (successCount == 0) {
                    return ReprocessResult.failure("Failed to retag any files");
                }
            }

            try {
                metadataWriter.writeMetadata(directoryPath, metadata, newVersion);
                log.info("Updated metadata file with version {}", newVersion);
            } catch (Exception ex) {
                log.error("Failed to update metadata file: {}", ex.getMessage());
            }

            try {
                recreateReleaseInDatabase(directoryPath, metadata, audioFiles, newVersion);
                log.info("Updated database with new version");
            } catch (Exception ex) {
                log.error("Failed to update database: {}", ex.getMessage());
            }

            String message = options.skipRetag()
                    ? String.format("Validated %d files and updated metadata (v%d)", audioFiles.size(), newVersion)
                    : String.format("Successfully retagged %d/%d files", successCount, audioFiles.size());
            log.info("Reprocessing completed: {}", message);

            return ReprocessResult.success(message, successCount, errorCount);

        } catch (Exception ex) {
            log.error("Failed to reprocess {}: {}", directoryPath, ex.getMessage(), ex);
            return ReprocessResult.failure("Reprocessing failed: " + ex.getMessage());
        }
    }

    private List<Path> findAudioFiles(Path directory) {
        List<Path> audioFiles = new ArrayList<>();

        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isAudioFile)
                    .filter(this::isNotHiddenMacFile)
                    .forEach(audioFiles::add);
        } catch (Exception ex) {
            log.error("Failed to list files in {}: {}", directory, ex.getMessage());
        }

        return audioFiles;
    }

    private boolean isAudioFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return filename.endsWith(".mp3") ||
                filename.endsWith(".flac") ||
                filename.endsWith(".m4a") ||
                filename.endsWith(".ogg") ||
                filename.endsWith(".wav") ||
                filename.endsWith(".opus") ||
                filename.endsWith(".aac");
    }

    private boolean isNotHiddenMacFile(Path file) {
        String filename = file.getFileName().toString();
        return !filename.startsWith("._");
    }

    private void recreateReleaseInDatabase(String directoryPath, ReleaseMetadata metadata,
                                           List<Path> audioFiles, int newVersion) {
        try {
            log.info("Recreating release in database: {} - {}", metadata.artist(), metadata.title());

            List<Path> sortedFiles = new ArrayList<>(audioFiles);
            sortedFiles.sort(Comparator.comparing(a -> a.getFileName().toString()));

            List<FileOrganizer.OrganizedFile> organizedFiles = new ArrayList<>();

            for (Path file : sortedFiles) {
                AudioTagger.TrackInfo trackInfo = audioTagger.readTrackInfo(file);

                if (trackInfo != null) {
                    organizedFiles.add(new FileOrganizer.OrganizedFile(
                            file.toString(),
                            file.toString(),
                            trackInfo.title(),
                            trackInfo.artist(),
                            trackInfo.trackNumber()
                    ));
                    log.info("Read track from file {}: trackNumber={}, title='{}', artist='{}'",
                            file.getFileName(), trackInfo.trackNumber(), trackInfo.title(), trackInfo.artist());
                } else {
                    log.warn("Could not read track info from {}, skipping", file.getFileName());
                }
            }

            if (organizedFiles.isEmpty()) {
                log.error("No track info could be read from audio files");
                return;
            }

            Path coverFile = Paths.get(directoryPath).resolve("cover.jpg");
            String coverPath = Files.exists(coverFile) ? coverFile.toString() : null;

            releaseService.clearReleaseData(metadata.id());
            releaseService.saveRelease(metadata, directoryPath, coverPath, organizedFiles, newVersion);

            log.info("Successfully recreated release in database: {} tracks, sourceId={}",
                    organizedFiles.size(), metadata.id());

        } catch (Exception ex) {
            log.error("Failed to recreate release in database for {}: {}",
                    directoryPath, ex.getMessage(), ex);
        }
    }

    public record ReprocessResult(
            boolean success,
            String message,
            int filesProcessed,
            int errors
    ) {
        public static ReprocessResult success(String message, int processed, int errors) {
            return new ReprocessResult(true, message, processed, errors);
        }

        public static ReprocessResult failure(String message) {
            return new ReprocessResult(false, message, 0, 0);
        }
    }
}
