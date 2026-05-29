package com.sashkomusic.libraryagent.domain.service.sync;

import com.sashkomusic.libraryagent.domain.entity.*;
import com.sashkomusic.libraryagent.domain.model.TagChange;
import com.sashkomusic.libraryagent.domain.model.TrackTagChanges;
import com.sashkomusic.libraryagent.domain.repository.ArtistRepository;
import com.sashkomusic.libraryagent.domain.repository.LabelRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class TrackTagSyncService {

    private final TrackRepository trackRepository;
    private final AudioTagExtractor tagExtractor;
    private final TagChangeBatchCollector batchCollector;
    private final LabelRepository labelRepository;
    private final ArtistRepository artistRepository;

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${sync.library-path:/Users/okravch/my/sm/lib}")
    private String libraryPath;

    public TrackTagSyncService(
            TrackRepository trackRepository,
            AudioTagExtractor tagExtractor,
            TagChangeBatchCollector batchCollector,
            LabelRepository labelRepository,
            ArtistRepository artistRepository
    ) {
        this.trackRepository = trackRepository;
        this.tagExtractor = tagExtractor;
        this.batchCollector = batchCollector;
        this.labelRepository = labelRepository;
        this.artistRepository = artistRepository;
    }

    @Scheduled(fixedDelayString = "${sync.interval:300000}")
    @Transactional
    public void syncTracksFromFiles() {
        if (!syncEnabled) {
            log.debug("Track tag sync is disabled");
            return;
        }

        log.info("Starting track tag synchronization from library: {}", libraryPath);
        long startTime = System.currentTimeMillis();

        int totalTracks = 0;
        int updatedTracks = 0;
        int errorTracks = 0;

        try {
            List<Track> allTracks = trackRepository.findAll();
            totalTracks = allTracks.size();
            log.info("Found {} tracks to check", totalTracks);

            for (Track track : allTracks) {
                try {
                    if (syncTrackTags(track)) {
                        updatedTracks++;
                    }
                } catch (Exception e) {
                    errorTracks++;
                    log.error("Failed to sync track {}: {}", track.getLocalPath(), e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Sync completed in {}ms. Total: {}, Updated: {}, Errors: {}",
                    duration, totalTracks, updatedTracks, errorTracks);

        } catch (Exception e) {
            log.error("Track tag synchronization failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public boolean syncTrackTags(Track track) {
        if (track.getLocalPath() == null || track.getLocalPath().isEmpty()) {
            return false;
        }

        Path audioFile = Paths.get(track.getLocalPath());

        if (!Files.exists(audioFile)) {
            log.warn("Audio file not found: {}", audioFile);
            return false;
        }

        try {
            // Check if file was modified since last sync
            FileTime lastModified = Files.getLastModifiedTime(audioFile);
            LocalDateTime fileModifiedTime = LocalDateTime.ofInstant(
                    lastModified.toInstant(),
                    ZoneId.systemDefault()
            );

            // Skip if file hasn't been modified
            LocalDateTime lastSyncTime = getLastSyncTime(track);
            if (lastSyncTime != null && fileModifiedTime.isBefore(lastSyncTime)) {
                log.trace("Skipping unchanged file: {}", audioFile.getFileName());
                return false;
            }

            // Extract tags from file
            Map<String, String> fileTags = tagExtractor.extractAllTags(audioFile);

            if (fileTags.isEmpty()) {
                log.debug("No tags found in file: {}", audioFile.getFileName());
                return false;
            }

            // Merge tags (smart update - only changed tags) and collect changes
            TrackTagChanges trackChanges = mergeTagsAndCollectChanges(track, fileTags, audioFile);

            if (trackChanges.hasChanges()) {
                trackRepository.save(track);

                batchCollector.collectChanges(trackChanges);

                log.debug("Synced {} tag changes for track: {}",
                        trackChanges.getChanges().size(), track.getTitle());
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Failed to sync tags for {}: {}", audioFile, e.getMessage());
            return false;
        }
    }

    private TrackTagChanges mergeTagsAndCollectChanges(Track track, Map<String, String> fileTags, Path audioFile) {
        String artistName = track.getArtists().stream()
                .findFirst()
                .map(Artist::getName)
                .orElse("невідомий виконавець");

        TrackTagChanges trackChanges = new TrackTagChanges(
                track.getId(),
                track.getTitle(),
                artistName
        );

        // Track changes that affect filename
        boolean artistChanged = false;
        boolean titleChanged = false;
        boolean trackNumberChanged = false;
        String newArtistName = null;

        for (Map.Entry<String, String> entry : fileTags.entrySet()) {
            String tagName = entry.getKey();
            String newValue = entry.getValue();

            String currentValue = track.getTag(tagName).orElse(null);

            if (!Objects.equals(currentValue, newValue)) {
                trackChanges.addChange(new TagChange(tagName, currentValue, newValue));
                track.setTag(tagName, newValue);

                // Sync RATING and RATING WMP tags
                if (isRatingTag(tagName) && newValue != null && !newValue.isEmpty()) {
                    syncRatingTags(track, tagName, newValue, trackChanges);
                }

                if (isTitleTag(tagName) && newValue != null && !newValue.isEmpty()) {
                    log.info("Updating track title from '{}' to '{}'", track.getTitle(), newValue);
                    track.setTitle(newValue);
                    titleChanged = true;
                }

                if (isArtistTag(tagName) && newValue != null && !newValue.isEmpty()) {
                    newArtistName = newValue;
                    artistChanged = updateTrackArtist(track, newValue);
                }

                if (isTrackNumberTag(tagName) && newValue != null && !newValue.isEmpty()) {
                    trackNumberChanged = updateTrackNumber(track, newValue);
                }

                if (isPublisherTag(tagName) && newValue != null && !newValue.isEmpty()) {
                    updateReleaseLabel(track, newValue);
                }

                log.trace("Updated tag {} for track {}: '{}' -> '{}'",
                        tagName, track.getTitle(), currentValue, newValue);
            }
        }

        // Rename file if artist, title or track number changed
        if (artistChanged || titleChanged || trackNumberChanged) {
            String currentArtist = track.getArtists().stream()
                    .findFirst()
                    .map(Artist::getName)
                    .orElse("unknown");
            renameFileIfNeeded(track, audioFile, currentArtist);
        }

        return trackChanges;
    }

    private boolean isRatingTag(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "RATING".equals(upperTag) || "RATING WMP".equals(upperTag);
    }

    private void syncRatingTags(Track track, String changedTag, String newValue, TrackTagChanges trackChanges) {
        String upperTag = changedTag.toUpperCase();
        String otherTagName = "RATING".equals(upperTag) ? "RATING WMP" : "RATING";

        String currentOtherValue = track.getTag(otherTagName).orElse(null);

        if (!Objects.equals(currentOtherValue, newValue)) {
            trackChanges.addChange(new TagChange(otherTagName, currentOtherValue, newValue));
            track.setTag(otherTagName, newValue);
            log.debug("Synced {} to match {}: '{}'", otherTagName, changedTag, newValue);
        }
    }

    private boolean isTitleTag(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "TIT2".equals(upperTag) || "TITLE".equals(upperTag);
    }

    private boolean isPublisherTag(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "PUBLISHER".equals(upperTag) || "TPUB".equals(upperTag);
    }

    private boolean isArtistTag(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "TPE1".equals(upperTag) || "ARTIST".equals(upperTag);
    }

    private boolean isTrackNumberTag(String tagName) {
        String upperTag = tagName.toUpperCase();
        return "TRCK".equals(upperTag) || "TRACK".equals(upperTag);
    }

    private boolean updateTrackArtist(Track track, String newArtistName) {
        String currentArtistName = track.getArtists().stream()
                .findFirst()
                .map(Artist::getName)
                .orElse(null);

        if (currentArtistName != null && currentArtistName.equalsIgnoreCase(newArtistName)) {
            return false;
        }

        // Find or create artist
        Artist newArtist = artistRepository.findByName(newArtistName).orElse(null);
        if (newArtist == null) {
            log.info("Creating new artist: '{}'", newArtistName);
            newArtist = new Artist(newArtistName);
            newArtist = artistRepository.save(newArtist);
        }

        // Remove old artists and add new one
        track.getArtists().clear();
        track.addArtist(newArtist);

        log.info("Updated artist for track '{}': '{}' -> '{}'",
                track.getTitle(), currentArtistName, newArtistName);
        return true;
    }

    private boolean updateTrackNumber(Track track, String newTrackNumberStr) {
        try {
            // Handle formats like "5" or "5/12"
            String numberPart = newTrackNumberStr.contains("/")
                    ? newTrackNumberStr.split("/")[0]
                    : newTrackNumberStr;

            int newTrackNumber = Integer.parseInt(numberPart.trim());
            Integer currentTrackNumber = track.getTrackNumber();

            if (currentTrackNumber != null && currentTrackNumber == newTrackNumber) {
                return false;
            }

            log.info("Updating track number for '{}': {} -> {}",
                    track.getTitle(), currentTrackNumber, newTrackNumber);
            track.setTrackNumber(newTrackNumber);
            return true;
        } catch (NumberFormatException e) {
            log.warn("Invalid track number format: '{}'", newTrackNumberStr);
            return false;
        }
    }

    private void renameFileIfNeeded(Track track, Path currentPath, String artistName) {
        try {
            String extension = getExtension(currentPath);
            Integer trackNumber = track.getTrackNumber();
            String title = track.getTitle();

            if (trackNumber == null || title == null || title.isEmpty()) {
                log.warn("Cannot rename file - missing track number or title");
                return;
            }

            String newFilename = String.format("%02d. %s - %s.%s",
                    trackNumber,
                    sanitizeFilename(artistName),
                    sanitizeFilename(title),
                    extension);

            Path newPath = currentPath.getParent().resolve(newFilename);

            if (currentPath.equals(newPath)) {
                log.trace("File already has correct name: {}", newFilename);
                return;
            }

            if (Files.exists(newPath)) {
                log.warn("Cannot rename - file already exists: {}", newFilename);
                return;
            }

            Files.move(currentPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            track.setLocalPath(newPath.toString());

            log.info("Renamed file: {} -> {}", currentPath.getFileName(), newFilename);

        } catch (IOException e) {
            log.error("Failed to rename file {}: {}", currentPath.getFileName(), e.getMessage());
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

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[/\\\\:*?\"<>|]", "")
                .trim()
                .toLowerCase();
    }

    private void updateReleaseLabel(Track track, String labelName) {
        Release release = track.getRelease();
        if (release == null) {
            log.warn("Cannot update label - track {} has no release", track.getTitle());
            return;
        }

        if (release.getLabel() != null && release.getLabel().getName().equalsIgnoreCase(labelName)) {
            log.trace("Label already set to '{}' for release '{}'", labelName, release.getTitle());
            return;
        }

        Label label = labelRepository.findByName(labelName).orElse(null);

        if (label == null) {
            log.info("Creating new label: '{}'", labelName);
            label = new Label(labelName);
            label = labelRepository.save(label);
        }

        String oldLabelName = release.getLabel() != null ? release.getLabel().getName() : "null";
        release.setLabel(label);
        log.info("Updated label for release '{}': '{}' -> '{}'", release.getTitle(), oldLabelName, labelName);
    }

    private LocalDateTime getLastSyncTime(Track track) {
        return track.getTags().stream()
                .map(TrackTag::getLastSyncedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    @Transactional
    public boolean syncTrackByPath(Path filePath) {
        try {
            Track track = trackRepository.findByLocalPath(filePath.toString()).orElse(null);

            if (track == null) {
                log.debug("No track found for path: {}", filePath);
                return false;
            }

            log.debug("File watcher triggered sync for: {}", filePath.getFileName());
            return syncTrackTags(track);

        } catch (Exception e) {
            log.error("Failed to sync track by path {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    public void triggerManualSync() {
        log.info("Manual sync triggered");
        syncTracksFromFiles();
    }
}
