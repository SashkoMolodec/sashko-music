package com.sashkomusic.libraryagent.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tracks_analyzed")
@Getter
@Setter
public class TrackAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false, unique = true)
    private Track track;

    // Rhythmic Features
    @Column(precision = 6, scale = 2)
    private BigDecimal bpm;

    @Column(precision = 5, scale = 4)
    private BigDecimal danceability;

    @Column(name = "beats_loudness", precision = 8, scale = 4)
    private BigDecimal beatsLoudness;

    @Column(name = "onset_rate", precision = 8, scale = 4)
    private BigDecimal onsetRate;

    // MFCC Features (13 coefficients x 2 stats = 26 columns)
    @Column(name = "mfcc_1_mean", precision = 14, scale = 6)
    private BigDecimal mfcc1Mean;

    @Column(name = "mfcc_1_var", precision = 14, scale = 6)
    private BigDecimal mfcc1Var;

    @Column(name = "mfcc_2_mean", precision = 14, scale = 6)
    private BigDecimal mfcc2Mean;

    @Column(name = "mfcc_2_var", precision = 14, scale = 6)
    private BigDecimal mfcc2Var;

    @Column(name = "mfcc_3_mean", precision = 14, scale = 6)
    private BigDecimal mfcc3Mean;

    @Column(name = "mfcc_3_var", precision = 14, scale = 6)
    private BigDecimal mfcc3Var;

    @Column(name = "mfcc_4_mean", precision = 14, scale = 6)
    private BigDecimal mfcc4Mean;

    @Column(name = "mfcc_4_var", precision = 14, scale = 6)
    private BigDecimal mfcc4Var;

    @Column(name = "mfcc_5_mean", precision = 14, scale = 6)
    private BigDecimal mfcc5Mean;

    @Column(name = "mfcc_5_var", precision = 14, scale = 6)
    private BigDecimal mfcc5Var;

    @Column(name = "mfcc_6_mean", precision = 14, scale = 6)
    private BigDecimal mfcc6Mean;

    @Column(name = "mfcc_6_var", precision = 14, scale = 6)
    private BigDecimal mfcc6Var;

    @Column(name = "mfcc_7_mean", precision = 14, scale = 6)
    private BigDecimal mfcc7Mean;

    @Column(name = "mfcc_7_var", precision = 14, scale = 6)
    private BigDecimal mfcc7Var;

    @Column(name = "mfcc_8_mean", precision = 14, scale = 6)
    private BigDecimal mfcc8Mean;

    @Column(name = "mfcc_8_var", precision = 14, scale = 6)
    private BigDecimal mfcc8Var;

    @Column(name = "mfcc_9_mean", precision = 14, scale = 6)
    private BigDecimal mfcc9Mean;

    @Column(name = "mfcc_9_var", precision = 14, scale = 6)
    private BigDecimal mfcc9Var;

    @Column(name = "mfcc_10_mean", precision = 14, scale = 6)
    private BigDecimal mfcc10Mean;

    @Column(name = "mfcc_10_var", precision = 14, scale = 6)
    private BigDecimal mfcc10Var;

    @Column(name = "mfcc_11_mean", precision = 14, scale = 6)
    private BigDecimal mfcc11Mean;

    @Column(name = "mfcc_11_var", precision = 14, scale = 6)
    private BigDecimal mfcc11Var;

    @Column(name = "mfcc_12_mean", precision = 14, scale = 6)
    private BigDecimal mfcc12Mean;

    @Column(name = "mfcc_12_var", precision = 14, scale = 6)
    private BigDecimal mfcc12Var;

    @Column(name = "mfcc_13_mean", precision = 14, scale = 6)
    private BigDecimal mfcc13Mean;

    @Column(name = "mfcc_13_var", precision = 14, scale = 6)
    private BigDecimal mfcc13Var;

    // Timbral Features
    @Column(name = "spectral_centroid", precision = 10, scale = 2)
    private BigDecimal spectralCentroid;

    @Column(name = "spectral_rolloff", precision = 10, scale = 2)
    private BigDecimal spectralRolloff;

    @Column(precision = 8, scale = 6)
    private BigDecimal dissonance;

    // Energy Features
    @Column(precision = 8, scale = 2)
    private BigDecimal loudness;

    @Column(name = "dynamic_complexity", precision = 8, scale = 6)
    private BigDecimal dynamicComplexity;

    // Metadata
    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt = LocalDateTime.now();

    @Column(name = "analysis_version", length = 20)
    private String analysisVersion = "1.0";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public TrackAnalysis() {
    }

    public TrackAnalysis(Track track) {
        this.track = track;
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }

    public boolean isSuccessful() {
        return !hasError();
    }
}
