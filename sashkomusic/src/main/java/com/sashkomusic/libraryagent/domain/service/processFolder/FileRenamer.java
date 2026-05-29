package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
public class FileRenamer {

    public Path rename(Path oldPath, TrackMatch match, String artist) {
        try {
            String extension = getExtension(oldPath);
            String newFilename = String.format("%02d. %s - %s.%s",
                    match.trackNumber(),
                    sanitize(artist),
                    sanitize(match.trackTitle()),
                    extension);

            Path newPath = oldPath.getParent().resolve(newFilename);

            if (oldPath.equals(newPath)) {
                log.info("File already has correct name: {}", newFilename);
                return oldPath;
            }

            if (Files.exists(newPath)) {
                log.warn("File already exists: {}, skipping rename", newFilename);
                return oldPath;
            }

            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);

            log.info("Renamed: {} -> {}", oldPath.getFileName(), newFilename);
            return newPath;

        } catch (IOException ex) {
            log.error("Error renaming file {}: {}", oldPath.getFileName(), ex.getMessage());
            return oldPath;
        }
    }

    private String getExtension(Path path) {
        String filename = path.getFileName().toString();
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    private String sanitize(String filename) {
        // Remove illegal characters for filesystems: / \ : * ? " < > |
        return filename.replaceAll("[/\\\\:*?\"<>|]", "")
                .trim()
                .toLowerCase();
    }
}