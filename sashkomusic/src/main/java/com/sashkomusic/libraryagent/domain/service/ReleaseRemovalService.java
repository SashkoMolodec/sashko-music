package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseRemovalService {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReleaseRepository releaseRepository;

    @Value("${trash.base-path}")
    private String trashBasePath;

    public record RemovalResult(boolean success, String releaseTitle, String directoryPath,
                                String trashPath, String message) {}

    @Transactional
    public RemovalResult remove(Long releaseId) {
        Optional<Release> releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            log.warn("Remove release: not found id={}", releaseId);
            return new RemovalResult(false, null, null, null, "release not found in DB");
        }

        Release release = releaseOpt.get();
        String title = release.getTitle();
        String directoryPath = release.getDirectoryPath();

        log.info("Removing release id={} title='{}' dir='{}'", releaseId, title, directoryPath);

        MoveResult move = moveToTrash(directoryPath);

        releaseRepository.delete(release);
        log.info("Deleted release id={} from DB", releaseId);

        if (move.error != null) {
            return new RemovalResult(true, title, directoryPath, null,
                    "DB cleared but trash move failed: " + move.error);
        }
        return new RemovalResult(true, title, directoryPath, move.targetPath,
                move.targetPath != null ? "moved to trash" : "DB cleared (no files on disk)");
    }

    private record MoveResult(String targetPath, String error) {}

    private MoveResult moveToTrash(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return new MoveResult(null, null);
        }
        Path source = Paths.get(directoryPath);
        if (!Files.exists(source)) {
            log.warn("Directory does not exist on disk: {}", source);
            return new MoveResult(null, null);
        }

        try {
            Path trashRoot = Paths.get(trashBasePath);
            Files.createDirectories(trashRoot);

            String stamp = LocalDateTime.now().format(TS_FORMAT);
            String folderName = source.getFileName().toString();
            Path target = trashRoot.resolve(stamp + "__" + folderName);

            int suffix = 1;
            while (Files.exists(target)) {
                target = trashRoot.resolve(stamp + "__" + folderName + "_" + suffix++);
            }

            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Cross-device move — copy recursively then delete source
                copyDirectory(source, target);
                deleteDirectory(source);
            }
            log.info("Moved release dir to trash: {} -> {}", source, target);
            return new MoveResult(target.toString(), null);
        } catch (IOException ex) {
            log.error("Failed to move {} to trash: {}", source, ex.getMessage(), ex);
            return new MoveResult(null, ex.getMessage());
        }
    }

    private void copyDirectory(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (var stream = Files.walk(src)) {
            for (Path entry : stream.toList()) {
                Path target = dst.resolve(src.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) {
                            log.warn("Could not delete {}: {}", p, e.getMessage());
                        }
                    });
        }
    }
}
