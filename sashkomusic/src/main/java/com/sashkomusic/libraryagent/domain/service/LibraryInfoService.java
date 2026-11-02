package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackAnalysis;
import com.sashkomusic.libraryagent.domain.entity.TrackTag;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackAnalysisRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything the DB knows about a release or a track, flattened into text an LLM can reason over.
 * <p>
 * Metadata (label, year, genres, sub-library), the user's own DJ tags (rating / energy / function /
 * comment) and the Essentia audio profile (BPM, danceability, loudness, …) live in three different
 * tables; the agent needs them together to answer "what is this record, and how does it feel" and
 * to decide what the user is actually after. Read-only — never mutates tags.
 */
@Service
@RequiredArgsConstructor
public class LibraryInfoService {

    private final ReleaseRepository releaseRepository;
    private final TrackRepository trackRepository;
    private final TrackTagRepository trackTagRepository;
    private final TrackAnalysisRepository trackAnalysisRepository;

    @Transactional(readOnly = true)
    public Optional<String> releaseInfo(Long releaseId) {
        return releaseRepository.findById(releaseId).map(this::describeRelease);
    }

    /** Audio profile + DJ tags of one track; empty when the track id is unknown. */
    @Transactional(readOnly = true)
    public Optional<String> trackInfo(Long trackId) {
        return trackRepository.findById(trackId).map(track -> {
            Map<String, String> tags = tagsByName(List.of(track.getId()), track.getId());
            Optional<TrackAnalysis> analysis = trackAnalysisRepository.findByTrackId(trackId);
            var sb = new StringBuilder();
            sb.append(track.getTitle()).append(trackDuration(track)).append("\n");
            sb.append("теги: ").append(djTags(tags)).append("\n");
            sb.append("аудіо: ").append(analysis.filter(TrackAnalysis::isSuccessful)
                    .map(a -> audioProfile(List.of(a)))
                    .orElse("не проаналізований"));
            return sb.toString();
        });
    }

    /** "1. Title" lines in track order; empty when the release has no tracks in the DB. */
    @Transactional(readOnly = true)
    public Optional<String> trackList(Long releaseId) {
        List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(releaseId);
        if (tracks.isEmpty()) return Optional.empty();
        return Optional.of(tracks.stream()
                .map(t -> (t.getTrackNumber() != null ? t.getTrackNumber() + ". " : "") + t.getTitle())
                .collect(Collectors.joining("\n")));
    }

    private String describeRelease(Release release) {
        var sb = new StringBuilder();
        String artists = release.getArtists().stream().map(Artist::getName).sorted()
                .collect(Collectors.joining(", "));
        if (!artists.isBlank()) sb.append(artists).append(" — ");
        sb.append(release.getTitle());
        if (release.getInitialRelease() != null) sb.append(" (").append(release.getInitialRelease()).append(")");
        sb.append("\n");

        sb.append("лейбл: ").append(release.getLabel() != null ? release.getLabel().getName() : "—");
        sb.append(", sublibrary: ").append(release.getSublibrary());
        if (release.getReleaseType() != null) sb.append(", тип: ").append(release.getReleaseType());
        sb.append(", формат: ").append(release.getReleaseFormat()).append("\n");

        String genres = release.getTags().stream().map(Tag::getName)
                .filter(n -> n != null && !n.isBlank()).sorted().collect(Collectors.joining(", "));
        sb.append("жанри: ").append(genres.isBlank() ? "—" : genres).append("\n");

        List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(release.getId());
        sb.append("треків: ").append(tracks.size()).append("\n");
        if (tracks.isEmpty()) return sb.toString().strip();

        List<Long> trackIds = tracks.stream().map(Track::getId).toList();
        Map<Long, Map<String, String>> tagsByTrack = trackTagRepository.findAllByTrackIds(trackIds).stream()
                .collect(Collectors.groupingBy(tt -> tt.getTrack().getId(),
                        Collectors.toMap(TrackTag::getTagName, TrackTag::getTagValue, (a, b) -> a)));
        Map<Long, TrackAnalysis> analysisByTrack = trackAnalysisRepository.findByTrackIdIn(trackIds).stream()
                .filter(TrackAnalysis::isSuccessful)
                .collect(Collectors.toMap(a -> a.getTrack().getId(), Function.identity(), (a, b) -> a));

        sb.append("аудіо (середнє по ").append(analysisByTrack.size()).append(" проаналізованих): ")
                .append(analysisByTrack.isEmpty() ? "нема аналізу" : audioProfile(List.copyOf(analysisByTrack.values())))
                .append("\n");

        for (Track t : tracks) {
            Map<String, String> tags = tagsByTrack.getOrDefault(t.getId(), Map.of());
            sb.append("- ");
            if (t.getTrackNumber() != null) sb.append(t.getTrackNumber()).append(". ");
            sb.append(t.getTitle());
            TrackAnalysis a = analysisByTrack.get(t.getId());
            if (a != null && a.getBpm() != null) sb.append(" | bpm ").append(fmt(a.getBpm(), 0));
            String dj = djTags(tags);
            if (!dj.equals("—")) sb.append(" | ").append(dj);
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private Map<String, String> tagsByName(List<Long> trackIds, Long trackId) {
        return trackTagRepository.findAllByTrackIds(trackIds).stream()
                .filter(tt -> tt.getTrack().getId().equals(trackId))
                .collect(Collectors.toMap(TrackTag::getTagName, TrackTag::getTagValue, (a, b) -> a));
    }

    private static String djTags(Map<String, String> tags) {
        var parts = new java.util.ArrayList<String>();
        int stars = TrackDto.starsOf(tags.get("RATING"));
        if (stars > 0) parts.add("рейтинг " + stars + "/5");
        addIfPresent(parts, "енергія", tags.get("DJ_ENERGY"));
        addIfPresent(parts, "функція", tags.get("DJ_FUNCTION"));
        addIfPresent(parts, "комент", tags.get("COMM"));
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private static void addIfPresent(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) parts.add(label + " " + value);
    }

    private static String audioProfile(List<TrackAnalysis> analyses) {
        var parts = new java.util.ArrayList<String>();
        addAvg(parts, "bpm", analyses, TrackAnalysis::getBpm, 0);
        addAvg(parts, "danceability", analyses, TrackAnalysis::getDanceability, 2);
        addAvg(parts, "loudness", analyses, TrackAnalysis::getLoudness, 1);
        addAvg(parts, "dynamic complexity", analyses, TrackAnalysis::getDynamicComplexity, 2);
        addAvg(parts, "onset rate", analyses, TrackAnalysis::getOnsetRate, 1);
        addAvg(parts, "spectral centroid", analyses, TrackAnalysis::getSpectralCentroid, 0);
        addAvg(parts, "dissonance", analyses, TrackAnalysis::getDissonance, 2);
        return parts.isEmpty() ? "нема даних" : String.join(", ", parts);
    }

    private static void addAvg(List<String> parts, String label, List<TrackAnalysis> analyses,
                               Function<TrackAnalysis, BigDecimal> getter, int scale) {
        double[] values = analyses.stream().map(getter).filter(v -> v != null).mapToDouble(BigDecimal::doubleValue).toArray();
        if (values.length == 0) return;
        double avg = java.util.Arrays.stream(values).average().orElse(0);
        parts.add(label + " " + String.format(Locale.ROOT, "%." + scale + "f", avg));
    }

    private static String fmt(BigDecimal value, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", value.doubleValue());
    }

    private static String trackDuration(Track track) {
        if (track.getDuration() == null) return "";
        return " (%d:%02d)".formatted(track.getDuration() / 60, track.getDuration() % 60);
    }
}
