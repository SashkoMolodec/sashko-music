package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackMatcher {

    public Map<String, TrackMatch> match(List<Path> audioFiles, ReleaseMetadata metadata) {
        log.info("Starting matching process for {} files", audioFiles.size());

        List<Path> sortedFiles = audioFiles.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        Map<String, TrackMatch> matches = matchFromExistingTags(sortedFiles, metadata);
        if (matches.size() == sortedFiles.size()) {
            log.info("All {} files matched by tags", matches.size());
            return matches;
        }

        List<Path> unmatchedFiles = sortedFiles.stream()
                .filter(file -> !matches.containsKey(file.toString()))
                .toList();

        if (!unmatchedFiles.isEmpty()) {
            log.info("Matched {} files by tags, {} files need filename matching",
                    matches.size(), unmatchedFiles.size());

            if (hasDuplicateTrackNumbers(unmatchedFiles, matches, metadata)) {
                log.info("Duplicates detected in filename track numbers, using sequential matching");
                return matchSequentially(sortedFiles, metadata);
            }

            matchByFilename(unmatchedFiles, matches, metadata);
        }

        return matches;
    }

    private boolean hasDuplicateTrackNumbers(List<Path> files, Map<String, TrackMatch> existingMatches,
                                               ReleaseMetadata metadata) {
        Set<Integer> usedTrackNumbers = existingMatches.values().stream()
                .map(TrackMatch::trackNumber)
                .collect(Collectors.toSet());

        for (Path file : files) {
            Integer trackNum = extractTrackNumber(file.getFileName().toString(), metadata);
            if (trackNum != null && trackNum > 0) {
                if (usedTrackNumbers.contains(trackNum)) {
                    return true;
                }
                usedTrackNumbers.add(trackNum);
            }
        }
        return false;
    }

    private void matchByFilename(List<Path> unmatchedFiles, Map<String, TrackMatch> matches,
                                  ReleaseMetadata metadata) {
        int nextTrackNum = matches.values().stream()
                .mapToInt(TrackMatch::trackNumber)
                .max()
                .orElse(0) + 1;

        for (Path file : unmatchedFiles) {
            TrackMatch match = matchSingleFile(file, metadata, nextTrackNum);
            matches.put(file.toString(), match);
            nextTrackNum = Math.max(nextTrackNum, match.trackNumber()) + 1;
        }
    }

    private TrackMatch matchSingleFile(Path file, ReleaseMetadata metadata, int fallbackTrackNum) {
        String filename = file.getFileName().toString();
        Integer trackNumber = extractTrackNumber(filename, metadata);

        // Case 1: Track number within metadata range - use official track
        if (trackNumber != null && metadata.tracks() != null && !metadata.tracks().isEmpty()
                && trackNumber > 0 && trackNumber <= metadata.tracks().size()) {
            var trackMetadata = metadata.tracks().get(trackNumber - 1);
            log.debug("File '{}' matched to official track {}", filename, trackNumber);
            return new TrackMatch(trackNumber, trackMetadata.artist(), trackMetadata.title());
        }

        // Case 2: Track number beyond metadata - bonus track with extracted title
        if (trackNumber != null && trackNumber > 0) {
            String title = extractTitle(filename);
            log.debug("File '{}' is bonus track {} (beyond metadata)", filename, trackNumber);
            return new TrackMatch(trackNumber, metadata.artist(), title);
        }

        // Case 3: No track number in filename - use provided fallback number
        String title = extractTitle(filename);
        log.warn("No track number found in '{}', assigning track {}", filename, fallbackTrackNum);
        return new TrackMatch(fallbackTrackNum, metadata.artist(), title);
    }

    private Map<String, TrackMatch> matchSequentially(List<Path> sortedFiles, ReleaseMetadata metadata) {
        Map<String, TrackMatch> matches = new HashMap<>();
        List<TrackMetadata> tracks = metadata.tracks();
        boolean hasMetadata = tracks != null && !tracks.isEmpty();

        for (int i = 0; i < sortedFiles.size(); i++) {
            Path file = sortedFiles.get(i);
            int trackNumber = i + 1;

            String artist;
            String title;
            if (hasMetadata && i < tracks.size()) {
                var trackMetadata = tracks.get(i);
                artist = trackMetadata.artist();
                title = trackMetadata.title();
            } else {
                artist = metadata.artist();
                title = extractTitle(file.getFileName().toString());
            }

            matches.put(file.toString(), new TrackMatch(trackNumber, artist, title));
        }
        return matches;
    }

    public Map<String, TrackMatch> matchFromExistingTags(List<Path> audioFiles, ReleaseMetadata metadata) {
        if (metadata.tracks() == null || metadata.tracks().isEmpty()) {
            return new HashMap<>();
        }

        Map<String, TrackMatch> matchMap = new HashMap<>();
        Set<Integer> usedTrackNumbers = new HashSet<>();

        for (Path file : audioFiles) {
            try {
                String currentDir = file.getParent().getFileName().toString();

                AudioFile audioFile = AudioFileIO.read(file.toFile());
                Tag tag = audioFile.getTag();
                if (tag == null) {
                    log.debug("File '{}' has no tags, skipping", file.getFileName());
                    continue;
                }

                String trackStr = tag.getFirst(FieldKey.TRACK);
                int trackNum = parseTrackNumberFromTag(trackStr);

                if (usedTrackNumbers.contains(trackNum)) {
                    log.warn("Duplicate track number {} detected in tags (folder: {}). Skipping tag matching entirely.", trackNum, currentDir);
                    return new HashMap<>();
                }

                if (trackNum <= 0 || trackNum > metadata.tracks().size()) {
                    log.debug("File '{}' has track number {} which is outside metadata range (1-{}), skipping",
                            file.getFileName(), trackNum, metadata.tracks().size());
                    continue;
                }

                var trackMetadata = metadata.tracks().get(trackNum - 1);
                TrackMatch match = createTrackMatchFromTags(tag, trackMetadata, trackNum);

                usedTrackNumbers.add(trackNum);
                matchMap.put(file.toString(), match);

                log.debug("File '{}' matched by tags: track={}, title='{}', artist='{}'",
                        file.getFileName(), trackNum, match.trackTitle(), match.artist());

            } catch (Exception ex) {
                log.debug("Failed to read tags from '{}': {}", file.getFileName(), ex.getMessage());
                continue;
            }
        }
        return matchMap;
    }

    private Integer extractTrackNumber(String filename, ReleaseMetadata metadata) {
        // Try vinyl notation first (A1, A2, B1, B2, etc.)
        if (filename.matches("^[A-Z]\\d+.*")) {
            Integer vinylNumber = parseVinylNotation(filename, metadata);
            if (vinylNumber != null) {
                return vinylNumber;
            }
        }

        // Try regular numeric track number (01, 1, etc.)
        String numberPart = filename.replaceAll("[^0-9].*", "");
        if (!numberPart.isEmpty()) {
            try {
                return Integer.parseInt(numberPart);
            } catch (NumberFormatException ex) {
            }
        }

        return null;
    }

    private Integer parseVinylNotation(String filename, ReleaseMetadata metadata) {
        try {
            char side = filename.charAt(0);
            String numberStr = filename.substring(1).replaceAll("[^0-9].*", "");
            int trackOnSide = Integer.parseInt(numberStr);

            int totalTracks = metadata.tracks() != null ? metadata.tracks().size() : 0;
            int sideIndex = side - 'A';
            int tracksPerSide = totalTracks > 0 ? (totalTracks + 1) / 2 : 10;
            int absoluteTrackNumber = (sideIndex * tracksPerSide) + trackOnSide;

            log.debug("Vinyl notation {}{} -> track {}", side, trackOnSide, absoluteTrackNumber);
            return absoluteTrackNumber;
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractTitle(String filename) {
        // Remove extension
        String nameWithoutExt = filename.replaceAll("\\.[^.]+$", "");

        // Remove track number prefix (e.g., "05. ", "A1 ", "01 - ")
        nameWithoutExt = nameWithoutExt.replaceAll("^[A-Z]?\\d+[\\s.-]+", "");

        // Remove Bandcamp artist suffix (e.g., "Die Welt, by Amygdala" -> "Die Welt")
        nameWithoutExt = nameWithoutExt.replaceAll(",\\s*by\\s+[^,]+$", "");

        // If format is "artist - title", extract only title
        if (nameWithoutExt.contains(" - ")) {
            String[] parts = nameWithoutExt.split(" - ", 2);
            if (parts.length == 2 && !parts[1].isEmpty()) {
                return parts[1].trim();
            }
        }

        return nameWithoutExt.trim();
    }

    private int parseTrackNumberFromTag(String trackNumberStr) {
        String[] parts = trackNumberStr.split("[/\\\\]");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private TrackMatch createTrackMatchFromTags(Tag tag, TrackMetadata trackMetadata, int trackNum) {
        String title = readTitleFromTag(tag, trackMetadata);
        String artist = readArtistFromTag(tag, trackMetadata);
        return new TrackMatch(trackNum, artist, title);
    }

    private String readTitleFromTag(Tag tag, TrackMetadata trackMetadata) {
        String titleFromTag = tag.getFirst(FieldKey.TITLE);
        if (titleFromTag != null && !titleFromTag.isEmpty()) {
            return titleFromTag;
        }
        return trackMetadata.title();
    }

    private String readArtistFromTag(Tag tag, TrackMetadata trackMetadata) {
        String artistFromTag = tag.getFirst(FieldKey.ARTIST);
        if (artistFromTag != null && !artistFromTag.isEmpty()) {
            return artistFromTag;
        }
        return trackMetadata.artist();
    }
}