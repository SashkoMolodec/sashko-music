package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.model.*;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.service.ReleaseService;
import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryProcessingService {

    @Value("${processing.version:1}")
    private int processingVersion;

    private final FileValidator fileValidator;
    private final CoverArtService coverArtService;
    private final TrackMatcher trackMatcher;
    private final FileRenamer fileRenamer;
    private final AudioTagger audioTagger;
    private final ReleaseService releaseService;
    private final FileOrganizer fileOrganizer;
    private final LibraryConfig libraryConfig;
    private final ReleaseMetadataWriter metadataWriter;

    public ProcessingResult processLibrary(ProcessLibraryTaskDto task) {
        log.info("Starting library processing for chatId={}, directory={}",
                task.chatId(), task.directoryPath());

        ReleaseMetadata metadata = task.metadata();
        log.info("Metadata: artist='{}', title='{}', trackTitles={}",
                metadata.artist(), metadata.title(),
                metadata.trackTitles() != null ? metadata.trackTitles().size() + " tracks" : "NULL");

        ValidationResult validation = fileValidator.validate(task);
        if (!validation.isValid()) {
            log.error("Validation failed: {}", validation.getErrorMessage());
            return ProcessingResult.failure(validation.getErrorMessage(), List.of());
        }

        byte[] coverArt = coverArtService.getCoverArt(metadata, task.directoryPath());
        return processFiles(task, metadata, coverArt);
    }

    private ProcessingResult processFiles(ProcessLibraryTaskDto task, ReleaseMetadata metadata, byte[] coverArt) {
        List<String> errors = new ArrayList<>();

        List<Path> audioFiles = collectAudioFiles(task.downloadedFiles());
        if (audioFiles.isEmpty()) {
            return ProcessingResult.failure("No audio files found", errors);
        }

        Map<String, TrackMatch> matchMap = trackMatcher.match(audioFiles, metadata);
        List<ProcessedFile> processedFiles = createProcessedFiles(audioFiles, matchMap, errors);

        if (processedFiles.isEmpty()) {
            return ProcessingResult.failure("No files were successfully processed", errors);
        }

        OrganizationContext orgContext = organizeIntoLibrary(processedFiles, metadata, task, coverArt, errors);
        saveToDatabase(metadata, orgContext.directoryPath, orgContext.coverPath, orgContext.organizedFiles, errors);

        log.info("Library processing completed successfully: {} files processed", processedFiles.size());
        return ProcessingResult.success(orgContext.directoryPath, processedFiles, errors);
    }

    private List<Path> collectAudioFiles(List<String> filePaths) {
        List<Path> audioFiles = new ArrayList<>();
        for (String filePath : filePaths) {
            Path file = Paths.get(filePath);
            if (Files.isRegularFile(file) && isAudioFile(file)) {
                audioFiles.add(file);
            } else {
                log.debug("Skipping non-audio file: {}", file.getFileName());
            }
        }
        log.info("Collected {} audio files", audioFiles.size());
        return audioFiles;
    }

    private List<ProcessedFile> createProcessedFiles(List<Path> audioFiles, Map<String, TrackMatch> matchMap,
                                                     List<String> errors) {
        List<ProcessedFile> results = new ArrayList<>();
        int fileIndex = 0;

        for (Path file : audioFiles) {
            fileIndex++;
            log.info("Processing file {}/{} : {}", fileIndex, audioFiles.size(), file.getFileName());

            try {
                TrackMatch match = matchMap.get(file.toString());
                if (match == null) {
                    throw new IllegalStateException("No match found for file: " + file.getFileName());
                }

                ProcessedFile processedFile = processFile(file, match);
                results.add(processedFile);

            } catch (Exception ex) {
                log.error("Error processing file {}/{} ({}): {}", fileIndex, audioFiles.size(),
                        file.getFileName(), ex.getMessage(), ex);
                errors.add("Failed to process " + file.getFileName() + ": " + ex.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Processing completed with {} errors out of {} files", errors.size(), audioFiles.size());
        }

        return results;
    }

    private OrganizationContext organizeIntoLibrary(List<ProcessedFile> processedFiles, ReleaseMetadata metadata,
                                                    ProcessLibraryTaskDto task, byte[] coverArt, List<String> errors) {
        String directoryPath = task.directoryPath();
        String coverPath = null;
        List<FileOrganizer.OrganizedFile> organizedFiles;

        if (!libraryConfig.getOrganization().isEnabled()) {
            organizedFiles = processedFiles.stream()
                    .map(pf -> new FileOrganizer.OrganizedFile(
                            pf.originalPath(), pf.newPath(), pf.trackTitle(), pf.trackArtist(), pf.trackNumber()))
                    .toList();
            return new OrganizationContext(directoryPath, coverPath, organizedFiles);
        }

        try {
            FileOrganizer.OrganizationResult organized = fileOrganizer.organize(
                    metadata, task.directoryPath(), processedFiles, libraryConfig.getRootPath());

            directoryPath = organized.newDirectoryPath();
            coverPath = organized.newCoverPath();
            organizedFiles = organized.files();

            log.info("Files organized into library structure: {}", directoryPath);

            organizedFiles = renameAndTagInLibrary(organizedFiles, metadata, coverArt, errors);

        } catch (Exception ex) {
            log.error("Failed to organize files: {}", ex.getMessage(), ex);
            errors.add("Failed to organize files: " + ex.getMessage());
            organizedFiles = processedFiles.stream()
                    .map(pf -> new FileOrganizer.OrganizedFile(
                            pf.originalPath(), pf.newPath(), pf.trackTitle(), pf.trackArtist(), pf.trackNumber()))
                    .toList();
        }

        return new OrganizationContext(directoryPath, coverPath, organizedFiles);
    }

    private List<FileOrganizer.OrganizedFile> renameAndTagInLibrary(
            List<FileOrganizer.OrganizedFile> organizedFiles, ReleaseMetadata metadata,
            byte[] coverArt, List<String> errors) {

        List<FileOrganizer.OrganizedFile> finalFiles = new ArrayList<>();

        for (FileOrganizer.OrganizedFile orgFile : organizedFiles) {
            try {
                Path copiedFile = Paths.get(orgFile.newPath());
                TrackMatch match = new TrackMatch(orgFile.trackNumber(), orgFile.trackArtist(), orgFile.trackTitle());

                Path renamedFile = fileRenamer.rename(copiedFile, match, match.artist());
                log.info("Renamed in library: {} -> {}", copiedFile.getFileName(), renamedFile.getFileName());

                audioTagger.tagFile(renamedFile, metadata, match, coverArt);
                log.info("Tagged in library: {}", renamedFile.getFileName());

                finalFiles.add(new FileOrganizer.OrganizedFile(
                        orgFile.oldPath(), renamedFile.toString(), orgFile.trackTitle(),
                        orgFile.trackArtist(), orgFile.trackNumber()));

            } catch (Exception ex) {
                log.error("Failed to rename/tag {}: {}", orgFile.newPath(), ex.getMessage(), ex);
                errors.add("Failed to process " + Paths.get(orgFile.newPath()).getFileName() + ": " + ex.getMessage());
                finalFiles.add(orgFile);
            }
        }

        return finalFiles;
    }

    private void saveToDatabase(ReleaseMetadata metadata, String directoryPath, String coverPath,
                                List<FileOrganizer.OrganizedFile> organizedFiles, List<String> errors) {
        try {
            releaseService.saveRelease(metadata, directoryPath, coverPath, organizedFiles, processingVersion);
            log.info("Release saved to database successfully");
            metadataWriter.writeMetadata(directoryPath, metadata, processingVersion);

        } catch (Exception ex) {
            log.error("Failed to save release to database: {}", ex.getMessage(), ex);
            errors.add("Failed to save release to database: " + ex.getMessage());
        }
    }

    private record OrganizationContext(
            String directoryPath,
            String coverPath,
            List<FileOrganizer.OrganizedFile> organizedFiles
    ) {}

    private ProcessedFile processFile(Path file, TrackMatch match) {
        String originalPath = file.toString();
        log.info("Matched {} to track {}: {} by {}", file.getFileName(), match.trackNumber(), match.trackTitle(), match.artist());

        return new ProcessedFile(
                originalPath,
                originalPath,
                match.trackTitle(),
                match.artist(),
                match.trackNumber()
        );
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

    private String extractTitleFromFilename(String filename) {
        // Remove extension
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");

        // Remove track number prefix (e.g., "05. ", "A1 ", "01 - ")
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // If format is "artist - title", extract only title
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2) {
                return parts[1].trim().toLowerCase();
            }
        }

        return nameWithoutExt.trim().toLowerCase();
    }

    private String extractArtistFromFilename(Path file, String releaseArtist) {
        String filename = file.getFileName().toString();

        // Remove extension and track number
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // If format is "artist - title", extract artist
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                return parts[0].trim();
            }
        }

        // Try reading artist from existing file tags
        try {
            AudioTagger.TrackInfo trackInfo = audioTagger.readTrackInfo(file);
            if (trackInfo != null && trackInfo.artist() != null && !trackInfo.artist().isEmpty()) {
                return trackInfo.artist();
            }
        } catch (Exception e) {
            log.debug("Could not read artist from file tags for {}: {}", filename, e.getMessage());
        }

        // Fallback to release artist
        return releaseArtist;
    }

    public record ProcessingResult(
            boolean success,
            String message,
            String directoryPath,
            List<ProcessedFile> processedFiles,
            List<String> errors
    ) {
        public static ProcessingResult success(String directoryPath, List<ProcessedFile> files, List<String> errors) {
            String msg = String.format("Successfully processed %d files", files.size());
            if (!errors.isEmpty()) {
                msg += String.format(" (with %d errors)", errors.size());
            }
            return new ProcessingResult(true, msg, directoryPath, files, errors);
        }

        public static ProcessingResult failure(String message, List<String> errors) {
            return new ProcessingResult(false, message, "", List.of(), errors);
        }
    }
}