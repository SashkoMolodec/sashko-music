package com.sashkomusic.libraryagent.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Audio-feature similarity over the user's own library — no external API, no LLM. Uses the
 * Essentia features already computed by sm-audio-analyzer and stored in tracks_analyzed
 * (rhythmic/MFCC/timbral/energy — see TrackAnalysis entity) to answer "I have this release,
 * find something like it in my own collection" purely by audio content, independent of
 * genre tags or metadata.
 *
 * Cosine similarity needs every feature on a comparable scale — bpm (~60-180) and an MFCC
 * variance (~0.001) can't be compared raw — so every feature column is z-score normalized
 * (mean 0, stddev 1) across the whole analyzed corpus before comparing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibrarySimilarityService {

    // Named explicitly (not by column position) so this stays correct if the SELECT order changes.
    private static final String[] FEATURE_COLUMNS = {
            "bpm", "danceability", "beats_loudness", "onset_rate",
            "mfcc_1_mean", "mfcc_1_var", "mfcc_2_mean", "mfcc_2_var",
            "mfcc_3_mean", "mfcc_3_var", "mfcc_4_mean", "mfcc_4_var",
            "mfcc_5_mean", "mfcc_5_var", "mfcc_6_mean", "mfcc_6_var",
            "mfcc_7_mean", "mfcc_7_var", "mfcc_8_mean", "mfcc_8_var",
            "mfcc_9_mean", "mfcc_9_var", "mfcc_10_mean", "mfcc_10_var",
            "mfcc_11_mean", "mfcc_11_var", "mfcc_12_mean", "mfcc_12_var",
            "mfcc_13_mean", "mfcc_13_var",
            "spectral_centroid", "spectral_rolloff", "dissonance",
            "loudness", "dynamic_complexity"
    };

    private static final String FEATURE_SQL = """
            SELECT ta.track_id, t.title, t.release_id, r.title AS release_title,
                   (SELECT string_agg(a.name, ', ' ORDER BY a.name)
                    FROM track_artists tta JOIN artists a ON a.id = tta.artist_id
                    WHERE tta.track_id = t.id) AS artists,
                   ta.bpm, ta.danceability, ta.beats_loudness, ta.onset_rate,
                   ta.mfcc_1_mean, ta.mfcc_1_var, ta.mfcc_2_mean, ta.mfcc_2_var,
                   ta.mfcc_3_mean, ta.mfcc_3_var, ta.mfcc_4_mean, ta.mfcc_4_var,
                   ta.mfcc_5_mean, ta.mfcc_5_var, ta.mfcc_6_mean, ta.mfcc_6_var,
                   ta.mfcc_7_mean, ta.mfcc_7_var, ta.mfcc_8_mean, ta.mfcc_8_var,
                   ta.mfcc_9_mean, ta.mfcc_9_var, ta.mfcc_10_mean, ta.mfcc_10_var,
                   ta.mfcc_11_mean, ta.mfcc_11_var, ta.mfcc_12_mean, ta.mfcc_12_var,
                   ta.mfcc_13_mean, ta.mfcc_13_var,
                   ta.spectral_centroid, ta.spectral_rolloff, ta.dissonance,
                   ta.loudness, ta.dynamic_complexity
            FROM tracks_analyzed ta
            JOIN tracks t ON t.id = ta.track_id
            JOIN releases r ON r.id = t.release_id
            WHERE ta.error_message IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public record SimilarTrack(Long trackId, String title, Long releaseId, String releaseTitle,
                                String artists, double similarity) {
    }

    private record TrackRow(Long trackId, String title, Long releaseId, String releaseTitle,
                             String artists, double[] features) {
    }

    /** Picks the first analyzed track on a release, to use as the similarity seed. */
    public Optional<Long> pickRepresentativeTrack(Long releaseId) {
        String sql = """
                SELECT ta.track_id FROM tracks_analyzed ta
                JOIN tracks t ON t.id = ta.track_id
                WHERE t.release_id = ? AND ta.error_message IS NULL
                ORDER BY t.track_number ASC NULLS LAST
                LIMIT 1
                """;
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, releaseId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    public List<SimilarTrack> findSimilarToTrack(Long seedTrackId, int limit) {
        List<TrackRow> rows = loadAll();
        TrackRow seed = rows.stream().filter(r -> r.trackId().equals(seedTrackId)).findFirst().orElse(null);
        if (seed == null || rows.size() < 2) {
            return List.of();
        }

        double[] mean = new double[FEATURE_COLUMNS.length];
        double[] std = new double[FEATURE_COLUMNS.length];
        computeStats(rows, mean, std);

        double[] seedVector = normalize(seed.features(), mean, std);

        return rows.stream()
                .filter(r -> !r.trackId().equals(seedTrackId) && !r.releaseId().equals(seed.releaseId()))
                .map(r -> new AbstractMap.SimpleEntry<>(r, cosineSimilarity(seedVector, normalize(r.features(), mean, std))))
                .sorted(Comparator.comparingDouble((java.util.Map.Entry<TrackRow, Double> e) -> e.getValue()).reversed())
                .limit(limit)
                .map(e -> new SimilarTrack(e.getKey().trackId(), e.getKey().title(), e.getKey().releaseId(),
                        e.getKey().releaseTitle(), e.getKey().artists(), e.getValue()))
                .toList();
    }

    private List<TrackRow> loadAll() {
        return jdbcTemplate.query(FEATURE_SQL, (rs, rowNum) -> {
            double[] features = new double[FEATURE_COLUMNS.length];
            for (int i = 0; i < FEATURE_COLUMNS.length; i++) {
                BigDecimal value = rs.getBigDecimal(FEATURE_COLUMNS[i]);
                features[i] = value != null ? value.doubleValue() : 0.0;
            }
            return new TrackRow(rs.getLong("track_id"), rs.getString("title"), rs.getLong("release_id"),
                    rs.getString("release_title"), rs.getString("artists"), features);
        });
    }

    private void computeStats(List<TrackRow> rows, double[] mean, double[] std) {
        int n = rows.size();
        for (TrackRow row : rows) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += row.features()[i] / n;
            }
        }
        for (TrackRow row : rows) {
            for (int i = 0; i < std.length; i++) {
                double diff = row.features()[i] - mean[i];
                std[i] += (diff * diff) / n;
            }
        }
        for (int i = 0; i < std.length; i++) {
            std[i] = Math.sqrt(std[i]);
            if (std[i] < 1e-9) std[i] = 1.0; // constant column — avoid divide-by-zero, contributes 0 either way
        }
    }

    private double[] normalize(double[] features, double[] mean, double[] std) {
        double[] result = new double[features.length];
        for (int i = 0; i < features.length; i++) {
            result[i] = (features[i] - mean[i]) / std[i];
        }
        return result;
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
