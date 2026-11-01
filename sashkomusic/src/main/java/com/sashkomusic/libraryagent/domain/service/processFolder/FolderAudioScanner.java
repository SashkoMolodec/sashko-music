package com.sashkomusic.libraryagent.domain.service.processFolder;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern SLSKD_RETRY_SUFFIX = Pattern.compile("^(.+)_\\d{15,}(\\.[^.]+)$");

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
        String stripped = rawCommand.length() > "/process".length()
                ? rawCommand.substring("/process".length()).trim()
                : "";
        return stripQuotes(stripped);
    }

    /**
     * Finds the folder the user means when the path they typed does not exist as written.
     * <p>
     * A pasted path is first matched by its tail under the downloads root, so any mount name on the
     * user's machine works. Failing that, sub-folders (one level nested) are scored by token overlap
     * with the LAST path segment only: the parents say where the folder is mounted, not what it is,
     * and counting them let {@code own_copy} outscore {@code own_copy/arnika}.
     */
    public Optional<Path> findClosestFolder(String query) {
        String q = stripQuotes(query).trim();
        if (q.isEmpty()) return Optional.empty();

        Path root = Paths.get(downloadsBasePath).normalize();
        if (!Files.isDirectory(root)) return Optional.empty();

        Optional<Path> byTail = findByPathTail(q, root);
        if (byTail.isPresent()) return byTail;

        List<String> tokens = List.of(lastSegment(q).toLowerCase().split("[^\\p{L}\\p{N}]+"));
        if (tokens.isEmpty()) return Optional.empty();

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

    /** "/Volumes/any/own_copy/arnika" → tries root/Volumes/…, root/any/…, root/own_copy/arnika, root/arnika; first folder that exists wins. */
    private Optional<Path> findByPathTail(String query, Path root) {
        if (!query.startsWith("/")) return Optional.empty();
        List<String> parts = Stream.of(query.split("/")).filter(s -> !s.isBlank()).toList();
        if (parts.contains("..")) return Optional.empty();
        for (int from = 0; from < parts.size(); from++) {
            Path candidate = root.resolve(String.join("/", parts.subList(from, parts.size()))).normalize();
            if (!candidate.equals(root) && candidate.startsWith(root) && Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
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
        List<Path> audio;
        try (Stream<Path> files = Files.walk(folderPath)) {
            audio = files
                    .filter(Files::isRegularFile)
                    .filter(this::isAudioFile)
                    .filter(this::isNotHiddenMacFile)
                    .toList();
        }
        Set<Path> all = Set.copyOf(audio);
        return audio.stream()
                .filter(p -> !isRetryCopy(p, all))
                .map(Path::toString)
                .toList();
    }

    /**
     * slskd keeps a re-downloaded file next to the original as "name_<ticks>.ext". Such a copy is only
     * dropped when the original is present, so a folder holding nothing but suffixed files stays intact.
     */
    static boolean isRetryCopy(Path file, Set<Path> siblings) {
        Matcher m = SLSKD_RETRY_SUFFIX.matcher(file.getFileName().toString());
        return m.matches() && siblings.contains(file.resolveSibling(m.group(1) + m.group(2)));
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
