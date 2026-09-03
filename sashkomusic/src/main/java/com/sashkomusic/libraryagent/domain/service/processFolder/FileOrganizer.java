package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.model.ProcessedFile;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileOrganizer {

    private static final int MAX_FOLDER_NAME_LENGTH = 200;

    public OrganizationResult organize(
            ReleaseMetadata metadata,
            String currentDirectory,
            List<ProcessedFile> processedFiles,
            String libraryRootPath,
            String sublibrary
    ) {
        try {
            // Determine folder structure
            String artistFolder = sanitizeFolderName(metadata.artist());
            String format = detectAudioFormat(processedFiles);
            Integer year = extractYear(metadata);
            String albumFolder = buildAlbumFolderName(metadata.title(), year, format);

            // Create target directory path: <root>/<sublibrary>/<artist>/<album>
            Path libraryRoot = Paths.get(libraryRootPath);
            Path targetDir = libraryRoot
                    .resolve(sublibrary)
                    .resolve(artistFolder)
                    .resolve(albumFolder);

            Files.createDirectories(targetDir);
            log.info("Created/verified directory: {}", targetDir);

            List<OrganizedFile> organizedFiles = new ArrayList<>();
            for (ProcessedFile file : processedFiles) {
                Path sourcePath = Paths.get(file.originalPath());

                String tempFileName = String.format("temp_%d_%s",
                        file.trackNumber(),
                        sourcePath.getFileName().toString());

                Path targetPath = targetDir.resolve(tempFileName);

                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied file to temporary location: {}", targetPath.getFileName());

                organizedFiles.add(new OrganizedFile(
                        file.originalPath(),
                        targetPath.toString(),
                        file.trackTitle(),
                        file.trackArtist(),
                        file.trackNumber()
                ));
            }

            // Copy cover art if exists (keep original as backup)
            String coverPath = null;
            Path sourceCover = Paths.get(currentDirectory).resolve("cover.jpg");
            if (Files.exists(sourceCover)) {
                Path targetCover = targetDir.resolve("cover.jpg");
                Files.copy(sourceCover, targetCover, StandardCopyOption.REPLACE_EXISTING);
                coverPath = targetCover.toString();
                log.info("Copied cover art to: {}", targetCover);
            }

            return new OrganizationResult(
                    targetDir.toString(),
                    coverPath,
                    organizedFiles
            );

        } catch (IOException e) {
            log.error("Failed to organize files: {}", e.getMessage(), e);
            throw new FileOrganizationException("Failed to organize files into library structure", e);
        }
    }

    private String sanitizeFolderName(String name) {
        if (name == null) {
            return "unknown";
        }

        // Normalize to NFC first: source metadata (esp. from Apple Music/gamdl) can mix
        // precomposed and decomposed Unicode forms, which breaks byte-exact path matching
        // in tools like Navidrome that don't normalize before comparing.
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);

        // Remove illegal filesystem characters
        String sanitized = normalized.replaceAll("[/\\\\:*?\"<>|]", "").trim();

        // Limit length
        if (sanitized.length() > MAX_FOLDER_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FOLDER_NAME_LENGTH).trim();
        }

        return sanitized.isEmpty() ? "unknown" : sanitized.toLowerCase();
    }

    private String detectAudioFormat(List<ProcessedFile> files) {
        Set<String> extensions = files.stream()
                .map(ProcessedFile::newPath)
                .map(path -> {
                    int lastDot = path.lastIndexOf('.');
                    return lastDot > 0 ? path.substring(lastDot + 1).toLowerCase() : "";
                })
                .collect(Collectors.toSet());

        if (extensions.size() == 1) {
            String ext = extensions.iterator().next();
            return switch (ext) {
                case "flac" -> "flac";
                case "mp3" -> "mp3";
                case "m4a" -> "m4a";
                case "ogg" -> "ogg";
                case "wav" -> "wav";
                case "opus" -> "opus";
                case "aac" -> "aac";
                default -> "digital";
            };
        }

        return "mixed";
    }

    private Integer extractYear(ReleaseMetadata metadata) {
        if (metadata.years() == null || metadata.years().isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(metadata.years().get(0));
        } catch (NumberFormatException e) {
            log.warn("Could not parse year: {}", metadata.years().get(0));
            return null;
        }
    }

    private String buildAlbumFolderName(String title, Integer year, String format) {
        String sanitizedTitle = sanitizeFolderName(title);

        if (year != null) {
            return String.format("%s (%d) [%s]", sanitizedTitle, year, format).toLowerCase();
        } else {
            return String.format("%s [%s]", sanitizedTitle, format).toLowerCase();
        }
    }

    public record OrganizationResult(
            String newDirectoryPath,
            String newCoverPath,
            List<OrganizedFile> files
    ) {}

    public record OrganizedFile(
            String oldPath,
            String newPath,
            String trackTitle,
            String trackArtist,
            int trackNumber
    ) {}

    public static class FileOrganizationException extends RuntimeException {
        public FileOrganizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
