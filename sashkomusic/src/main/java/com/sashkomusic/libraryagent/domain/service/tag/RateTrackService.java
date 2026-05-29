package com.sashkomusic.libraryagent.domain.service.tag;

import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateTrackService {

    private final TrackRepository trackRepository;
    private final AudioTagExtractor audioTagExtractor;
    private final DjTagWriter djTagWriter;

    public record RateResult(boolean success, String message) {}

    @Transactional
    public RateResult rateTrack(Long trackId, int rating) {
        log.info("Rating track id={} with rating={}", trackId, rating);

        if (rating < 1 || rating > 5) {
            return new RateResult(false, "рейтинг має бути від 1 до 5");
        }

        Track track = trackRepository.findById(trackId).orElse(null);
        if (track == null) {
            return new RateResult(false, "трек не знайдено");
        }

        Path audioFile = track.getLocalPath() != null ? Paths.get(track.getLocalPath()) : null;
        if (audioFile == null || !Files.exists(audioFile)) {
            return new RateResult(false, "файл не існує");
        }

        int ratingWmp = convertStarsToWmpRating(rating);
        track.setTag("RATING", String.valueOf(ratingWmp));
        track.setTag("RATING WMP", String.valueOf(ratingWmp));

        boolean success = audioTagExtractor.writeRating(audioFile, rating);
        trackRepository.save(track);

        if (success) {
            log.info("Successfully rated track: id={}, rating={}", trackId, rating);
            return new RateResult(true, "✅ рейтинг " + rating + "★");
        } else {
            return new RateResult(false, "помилка запису рейтингу");
        }
    }

    @Transactional
    public RateResult setEnergy(Long trackId, String energy) {
        log.info("Setting energy for track id={} to {}", trackId, energy);

        Track track = trackRepository.findById(trackId).orElse(null);
        if (track == null) {
            return new RateResult(false, "трек не знайдено");
        }

        track.setTag("DJ_ENERGY", energy);

        Path audioFile = track.getLocalPath() != null ? Paths.get(track.getLocalPath()) : null;
        if (audioFile != null && Files.exists(audioFile)) {
            djTagWriter.writeDjEnergy(audioFile, energy);
        }

        // Also add to COMM tag
        track.prependToTag("COMM", energy);
        if (audioFile != null && Files.exists(audioFile)) {
            djTagWriter.prependEnergy(audioFile, energy);
        }

        trackRepository.save(track);
        log.info("Successfully set energy for track: id={}, energy={}", trackId, energy);
        return new RateResult(true, "✅ energy " + energy);
    }

    @Transactional
    public RateResult setFunction(Long trackId, String function) {
        log.info("Setting function for track id={} to {}", trackId, function);

        Track track = trackRepository.findById(trackId).orElse(null);
        if (track == null) {
            return new RateResult(false, "трек не знайдено");
        }

        track.setTag("DJ_FUNCTION", function);

        Path audioFile = track.getLocalPath() != null ? Paths.get(track.getLocalPath()) : null;
        if (audioFile != null && Files.exists(audioFile)) {
            djTagWriter.writeDjFunction(audioFile, function);
        }

        // Also add to COMM tag
        track.prependToTag("COMM", function);
        if (audioFile != null && Files.exists(audioFile)) {
            djTagWriter.prependFunction(audioFile, function);
        }

        trackRepository.save(track);
        log.info("Successfully set function for track: id={}, function={}", trackId, function);
        return new RateResult(true, "✅ function " + function);
    }

    @Transactional
    public RateResult addComment(Long trackId, String comment) {
        log.info("Adding comment for track id={}: {}", trackId, comment);

        Track track = trackRepository.findById(trackId).orElse(null);
        if (track == null) {
            return new RateResult(false, "трек не знайдено");
        }

        track.prependToTag("COMM", comment);

        Path audioFile = track.getLocalPath() != null ? Paths.get(track.getLocalPath()) : null;
        if (audioFile != null && Files.exists(audioFile)) {
            djTagWriter.prependCommentText(audioFile, comment);
        }

        trackRepository.save(track);
        log.info("Successfully added comment for track: id={}", trackId);
        return new RateResult(true, "✅ коментар додано");
    }

    private int convertStarsToWmpRating(int stars) {
        return switch (stars) {
            case 1 -> 51;
            case 2 -> 102;
            case 3 -> 153;
            case 4 -> 204;
            case 5 -> 255;
            default -> 0;
        };
    }
}
