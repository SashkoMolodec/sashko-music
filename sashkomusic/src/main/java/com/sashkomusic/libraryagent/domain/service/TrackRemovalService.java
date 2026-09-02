package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackRemovalService {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReleaseRepository releaseRepository;
    private final TrackRepository trackRepository;
    private final ReleaseRemovalService releaseRemovalService;

    @Value("${trash.base-path}")
    private String trashBasePath;

    public record TrackRemovalResult(
            boolean success,
            List<String> removedTitles,
            List<Integer> notFoundNumbers,
            boolean releaseFullyRemoved,
            String message
    ) {}

    @Transactional
    public TrackRemovalResult removeTracks(Long releaseId, List<Integer> trackNumbers) {
        var releaseOpt = releaseRepository.findById(releaseId);
        if (releaseOpt.isEmpty()) {
            return new TrackRemovalResult(false, List.of(), List.of(), false, "реліз не знайдено в базі");
        }

        List<Track> allTracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(releaseId);
        Set<Integer> requested = new LinkedHashSet<>(trackNumbers);

        List<Track> toRemove = allTracks.stream()
                .filter(t -> t.getTrackNumber() != null && requested.contains(t.getTrackNumber()))
                .toList();

        Set<Integer> foundNumbers = toRemove.stream().map(Track::getTrackNumber).collect(Collectors.toSet());
        List<Integer> notFound = trackNumbers.stream().filter(n -> !foundNumbers.contains(n)).distinct().toList();

        if (toRemove.isEmpty()) {
            return new TrackRemovalResult(false, List.of(), notFound, false, "жоден із вказаних номерів не знайдено");
        }

        List<String> removedTitles = new ArrayList<>();
        List<String> fileFailures = new ArrayList<>();
        for (Track track : toRemove) {
            String label = (track.getTrackNumber() != null ? track.getTrackNumber() + ". " : "") + track.getTitle();
            removedTitles.add(label);
            if (!moveFileToTrash(track.getLocalPath())) {
                fileFailures.add(label);
            }
            trackRepository.delete(track);
            log.info("Removed track id={} '{}' from release {}", track.getId(), track.getTitle(), releaseId);
        }

        String fileWarning = fileFailures.isEmpty() ? null
                : "⚠️ файл(и) лишились на диску, не вдалося прибрати: " + String.join(", ", fileFailures);

        if (toRemove.size() == allTracks.size()) {
            releaseRemovalService.remove(releaseId);
            String msg = "усі треки видалено — реліз перенесено у trash";
            return new TrackRemovalResult(true, removedTitles, notFound, true,
                    fileWarning != null ? msg + "\n" + fileWarning : msg);
        }

        return new TrackRemovalResult(true, removedTitles, notFound, false, fileWarning);
    }

    /** @return true if the file was moved to trash (or there was nothing to move) */
    private boolean moveFileToTrash(String localPath) {
        if (localPath == null || localPath.isBlank()) return true;
        Path source = Paths.get(localPath);
        if (!Files.exists(source)) return true;

        try {
            Path trashRoot = Paths.get(trashBasePath);
            Files.createDirectories(trashRoot);

            String stamp = LocalDateTime.now().format(TS_FORMAT);
            Path target = trashRoot.resolve(stamp + "__" + source.getFileName());
            int suffix = 1;
            while (Files.exists(target)) {
                target = trashRoot.resolve(stamp + "__" + source.getFileName() + "_" + suffix++);
            }

            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(source);
            }
            log.info("Moved track file to trash: {} -> {}", source, target);
            return true;
        } catch (Exception e) {
            log.error("Failed to move track file to trash {}: {}", localPath, e.getMessage(), e);
            return false;
        }
    }
}
