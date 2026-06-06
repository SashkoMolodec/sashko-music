package com.sashkomusic.mainagent.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Filesystem-side concerns for a single processing folder:
 * resolve raw user input → an absolute {@link Path}, expose audio file listing,
 * and clean folder names for downstream display / search.
 */
@Slf4j
@Component
public class FolderAudioScanner {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "m4a", "ogg", "wav", "opus", "aac");

    @Value("${downloads.base-path:/Users/okravch/my/sm/downloads}")
    private String downloadsBasePath;

    public ResolvedFolder resolve(String rawInput) {
        String folderName = stripQuotes(rawInput).trim();
        Path inputPath = Paths.get(folderName);
        Path folderPath = inputPath.isAbsolute()
                ? inputPath
                : Paths.get(downloadsBasePath, folderName);
        String cleanedName = cleanFolderName(folderPath.getFileName().toString());
        return new ResolvedFolder(cleanedName, folderPath);
    }

    public String stripProcessPrefix(String rawCommand) {
        return stripQuotes(rawCommand.substring("/process ".length()).trim());
    }

    /**
     * Searches immediate (and one level nested) sub-folders of downloads root for the closest match to the user's query.
     * Token-based scoring: each lowercased alnum token in the query that appears in the folder name adds 1.
     */
    public Optional<Path> findClosestFolder(String query) {
        String q = stripQuotes(query).trim().toLowerCase();
        if (q.isEmpty()) return Optional.empty();
        List<String> tokens = List.of(q.split("[^\\p{L}\\p{N}]+"));
        if (tokens.isEmpty()) return Optional.empty();

        Path root = Paths.get(downloadsBasePath);
        if (!Files.isDirectory(root)) return Optional.empty();

        try (Stream<Path> stream = Files.walk(root, 2)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.equals(root))
                    .map(p -> new Scored(p, score(p.getFileName().toString().toLowerCase(), tokens)))
                    .filter(s -> s.score > 0)
                    .max(Comparator.<Scored>comparingInt(s -> s.score)
                            .thenComparing(s -> -s.path.getFileName().toString().length()))
                    .map(s -> s.path);
        } catch (IOException ex) {
            log.warn("Failed to search downloads folder: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private int score(String folderNameLower, List<String> tokens) {
        int s = 0;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (folderNameLower.contains(t)) s++;
        }
        return s;
    }

    private record Scored(Path path, int score) {}

    public List<String> listAudioFiles(Path folderPath) throws IOException {
        try (Stream<Path> files = Files.walk(folderPath)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(this::isAudioFile)
                    .filter(this::isNotHiddenMacFile)
                    .map(Path::toString)
                    .toList();
        }
    }

    private boolean isAudioFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return AUDIO_EXTENSIONS.stream().anyMatch(ext -> filename.endsWith("." + ext));
    }

    private boolean isNotHiddenMacFile(Path file) {
        return !file.getFileName().toString().startsWith("._");
    }

    static String stripQuotes(String path) {
        if (path == null || path.isEmpty()) return path;
        String trimmed = path.trim();
        if (trimmed.length() <= 1) return trimmed;
        if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String cleanFolderName(String folderName) {
        return folderName
                .replaceAll("\\[.*?]", "")
                .replaceAll("[()]", "")
                .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record ResolvedFolder(String name, Path path) {
        public boolean isDirectory() {
            return Files.exists(path) && Files.isDirectory(path);
        }
    }
}
