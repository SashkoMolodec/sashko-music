package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseRelocationService {

    private final ReleaseRepository releaseRepository;
    private final LibraryConfig libraryConfig;

    public record RelocationResult(
            boolean success,
            Release release,
            String title,
            String artistName,
            String oldPath,
            String newPath,
            String message
    ) {}

    @Transactional
    public RelocationResult move(Long releaseId, String targetSublibrary) {
        Optional<Release> releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return new RelocationResult(false, null, null, null, null, null, "release not found in DB");
        }

        if (!libraryConfig.getSublibraries().contains(targetSublibrary)) {
            Release r = releaseOpt.get();
            return new RelocationResult(false, r, r.getTitle(), primaryArtist(r), null, null,
                    "unknown sublibrary '" + targetSublibrary + "' — valid: " + libraryConfig.getSublibraries());
        }

        Release release = releaseOpt.get();
        String title = release.getTitle();
        String artistName = primaryArtist(release);
        String oldDir = release.getDirectoryPath();

        if (targetSublibrary.equals(release.getSublibrary())) {
            return new RelocationResult(true, release, title, artistName, oldDir, oldDir,
                    "already in " + targetSublibrary);
        }

        try {
            Path source = Paths.get(oldDir);
            Path target = computeTargetPath(source, targetSublibrary);

            if (Files.exists(source)) {
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    return new RelocationResult(false, release, title, artistName, oldDir, target.toString(),
                            "target already exists: " + target);
                }
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    log.warn("Atomic move not supported (cross-device?), falling back to copy+delete");
                    copyDirectory(source, target);
                    deleteDirectory(source);
                } catch (IOException ex) {
                    log.warn("Atomic move failed ({}), falling back to non-atomic", ex.getMessage());
                    Files.move(source, target);
                }
                log.info("Moved release {} -> {}", source, target);
            } else {
                log.warn("Source directory missing on disk, updating DB only: {}", source);
            }

            String oldPrefix = oldDir;
            String newPrefix = target.toString();
            release.setDirectoryPath(newPrefix);
            release.setSublibrary(targetSublibrary);

            if (release.getCoverPath() != null && release.getCoverPath().startsWith(oldPrefix)) {
                release.setCoverPath(release.getCoverPath().replace(oldPrefix, newPrefix));
            }
            for (Track track : release.getTracks()) {
                if (track.getLocalPath() != null && track.getLocalPath().startsWith(oldPrefix)) {
                    track.setLocalPath(track.getLocalPath().replace(oldPrefix, newPrefix));
                }
            }

            releaseRepository.save(release);
            return new RelocationResult(true, release, title, artistName, oldDir, newPrefix,
                    "moved to " + targetSublibrary);

        } catch (IOException ex) {
            log.error("Failed to move release {}: {}", oldDir, ex.getMessage(), ex);
            return new RelocationResult(false, release, title, artistName, oldDir, null,
                    "IO error: " + ex.getMessage());
        }
    }

    private static String primaryArtist(Release release) {
        if (release.getArtists() == null || release.getArtists().isEmpty()) return null;
        return release.getArtists().iterator().next().getName();
    }

    private Path computeTargetPath(Path source, String targetSublibrary) {
        Path rootPath = Paths.get(libraryConfig.getRootPath()).toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();

        Path relativeFromRoot = rootPath.relativize(normalizedSource);

        if (relativeFromRoot.getNameCount() >= 2 && knownSublibrary(relativeFromRoot.getName(0).toString())) {
            Path withoutSublib = relativeFromRoot.subpath(1, relativeFromRoot.getNameCount());
            return rootPath.resolve(targetSublibrary).resolve(withoutSublib);
        }
        return rootPath.resolve(targetSublibrary).resolve(relativeFromRoot);
    }

    private boolean knownSublibrary(String name) {
        return libraryConfig.getSublibraries().contains(name);
    }

    private void copyDirectory(Path src, Path dst) throws java.io.IOException {
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

    private void deleteDirectory(Path dir) throws java.io.IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (java.io.IOException e) {
                            log.warn("Could not delete {}: {}", p, e.getMessage());
                        }
                    });
        }
    }
}
