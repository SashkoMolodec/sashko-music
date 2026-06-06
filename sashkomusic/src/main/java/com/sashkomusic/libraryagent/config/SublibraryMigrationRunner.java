package com.sashkomusic.libraryagent.config;

import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SublibraryMigrationRunner implements ApplicationRunner {

    private final ReleaseRepository releaseRepository;
    private final LibraryConfig libraryConfig;

    public record MigrationStats(int migrated, int skipped, int failed) {
        public String summary() {
            return "✅ migrated=" + migrated + ", skipped=" + skipped + ", failed=" + failed;
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            MigrationStats stats = migrate();
            log.info("Sublibrary migration on boot: {}", stats.summary());
        } catch (Exception ex) {
            log.error("Sublibrary migration failed: {}", ex.getMessage(), ex);
        }
    }

    @Transactional
    public MigrationStats migrate() {
        String defaultSublib = libraryConfig.getDefaultSublibrary();
        Path root = Paths.get(libraryConfig.getRootPath()).toAbsolutePath().normalize();
        List<String> sublibs = libraryConfig.getSublibraries();

        List<Release> all = releaseRepository.findAll();
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        for (Release release : all) {
            String oldPath = release.getDirectoryPath();
            if (oldPath == null || oldPath.isBlank()) {
                skipped++;
                continue;
            }
            if (isAlreadyUnderSublib(oldPath, root, sublibs)) {
                skipped++;
                continue;
            }
            try {
                migrateOne(release, root, defaultSublib);
                migrated++;
            } catch (Exception ex) {
                failed++;
                log.error("Failed to migrate release id={} dir={}: {}",
                        release.getId(), oldPath, ex.getMessage(), ex);
            }
        }
        return new MigrationStats(migrated, skipped, failed);
    }

    private boolean isAlreadyUnderSublib(String dirPath, Path root, List<String> sublibs) {
        Path normalized = Paths.get(dirPath).toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return true; // outside library root — leave alone
        }
        Path relative = root.relativize(normalized);
        if (relative.getNameCount() == 0) return true;
        String firstSegment = relative.getName(0).toString();
        return sublibs.contains(firstSegment);
    }

    private void migrateOne(Release release, Path root, String defaultSublib) throws IOException {
        String oldPath = release.getDirectoryPath();
        Path source = Paths.get(oldPath).toAbsolutePath().normalize();
        Path relative = root.relativize(source);
        Path target = root.resolve(defaultSublib).resolve(relative);

        if (Files.exists(source)) {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                log.warn("Both source and target exist, will merge by leaving source in place is unsafe. Skipping FS move for {} (target already exists at {})", source, target);
            } else {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    Files.move(source, target);
                }
                log.info("Migrated dir: {} -> {}", source, target);
            }
        } else if (Files.exists(target)) {
            // FS already moved earlier (by hand or a previous run that crashed before DB update) — DB just needs to catch up.
            log.info("Source missing but target exists, updating DB only: {} (target={})", source, target);
        } else {
            log.warn("Source AND target dirs both missing on disk, updating DB only: {}", source);
        }

        String oldPrefix = oldPath;
        String newPrefix = target.toString();
        release.setDirectoryPath(newPrefix);
        release.setSublibrary(defaultSublib);

        if (release.getCoverPath() != null && release.getCoverPath().startsWith(oldPrefix)) {
            release.setCoverPath(release.getCoverPath().replace(oldPrefix, newPrefix));
        }
        for (Track track : release.getTracks()) {
            if (track.getLocalPath() != null && track.getLocalPath().startsWith(oldPrefix)) {
                track.setLocalPath(track.getLocalPath().replace(oldPrefix, newPrefix));
            }
        }
        releaseRepository.save(release);
    }
}
