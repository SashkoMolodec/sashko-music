package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartlistM3uWriter {

    private static final String DIR_NAME = "smartlists";

    private final LibraryConfig libraryConfig;

    public Path write(String smartlistName, List<Track> tracks) {
        Path dir = directory();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitize(smartlistName) + ".m3u");
            Path tmp = dir.resolve("." + sanitize(smartlistName) + ".m3u.tmp");

            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write("#EXTM3U");
                w.newLine();
                Path libraryRoot = libraryRoot();
                for (Track t : tracks) {
                    String localPath = t.getLocalPath();
                    if (localPath == null || localPath.isBlank()) continue;
                    int duration = t.getDuration() == null ? -1 : t.getDuration();
                    String artist = t.getArtists().stream().findFirst().map(Artist::getName).orElse("");
                    String title = t.getTitle() == null ? "" : t.getTitle();
                    w.write("#EXTINF:" + duration + "," + artist + " - " + title);
                    w.newLine();
                    w.write(toRelative(libraryRoot, Paths.get(localPath)));
                    w.newLine();
                }
            }

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Wrote smartlist M3U: {} ({} tracks)", target, tracks.size());
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write smartlist M3U for '" + smartlistName + "'", e);
        }
    }

    public boolean delete(String smartlistName) {
        Path target = directory().resolve(sanitize(smartlistName) + ".m3u");
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to delete smartlist M3U {}: {}", target, e.getMessage());
            return false;
        }
    }

    public boolean rename(String oldName, String newName) {
        Path from = directory().resolve(sanitize(oldName) + ".m3u");
        Path to = directory().resolve(sanitize(newName) + ".m3u");
        if (!Files.exists(from)) return false;
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            log.warn("Failed to rename smartlist M3U {} → {}: {}", from, to, e.getMessage());
            return false;
        }
    }

    private Path directory() {
        return libraryRoot().resolve(DIR_NAME);
    }

    private Path libraryRoot() {
        String root = libraryConfig.getRootPath();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("library.rootPath is not configured");
        }
        return Paths.get(root);
    }

    private String toRelative(Path root, Path track) {
        try {
            return root.resolve(DIR_NAME).relativize(track).toString();
        } catch (IllegalArgumentException e) {
            return track.toString();
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }
}
