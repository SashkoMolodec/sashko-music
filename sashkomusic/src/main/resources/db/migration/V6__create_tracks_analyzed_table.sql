-- Create tracks_analyzed table for Essentia audio analysis results
CREATE TABLE tracks_analyzed (
    id BIGSERIAL PRIMARY KEY,
    track_id BIGINT NOT NULL UNIQUE REFERENCES tracks(id) ON DELETE CASCADE,

    -- Rhythmic Features
    bpm DECIMAL(6,2),                    -- Tempo (beats per minute)
    danceability DECIMAL(5,4),           -- 0.0 to 1.0
    beats_loudness DECIMAL(8,4),         -- Average loudness of beats
    onset_rate DECIMAL(8,4),             -- Number of onsets per second

    -- Timbral Features (MFCC)
    mfcc_1_mean DECIMAL(10,6),           -- MFCC coefficient 1 mean
    mfcc_1_var DECIMAL(10,6),            -- MFCC coefficient 1 variance
    mfcc_2_mean DECIMAL(10,6),
    mfcc_2_var DECIMAL(10,6),
    mfcc_3_mean DECIMAL(10,6),
    mfcc_3_var DECIMAL(10,6),
    mfcc_4_mean DECIMAL(10,6),
    mfcc_4_var DECIMAL(10,6),
    mfcc_5_mean DECIMAL(10,6),
    mfcc_5_var DECIMAL(10,6),
    mfcc_6_mean DECIMAL(10,6),
    mfcc_6_var DECIMAL(10,6),
    mfcc_7_mean DECIMAL(10,6),
    mfcc_7_var DECIMAL(10,6),
    mfcc_8_mean DECIMAL(10,6),
    mfcc_8_var DECIMAL(10,6),
    mfcc_9_mean DECIMAL(10,6),
    mfcc_9_var DECIMAL(10,6),
    mfcc_10_mean DECIMAL(10,6),
    mfcc_10_var DECIMAL(10,6),
    mfcc_11_mean DECIMAL(10,6),
    mfcc_11_var DECIMAL(10,6),
    mfcc_12_mean DECIMAL(10,6),
    mfcc_12_var DECIMAL(10,6),
    mfcc_13_mean DECIMAL(10,6),
    mfcc_13_var DECIMAL(10,6),

    spectral_centroid DECIMAL(10,2),     -- Average frequency centroid (Hz)
    spectral_rolloff DECIMAL(10,2),      -- Rolloff frequency (Hz)
    dissonance DECIMAL(8,6),             -- Harmonic dissonance measure

    -- Energy Features
    loudness DECIMAL(8,2),               -- EBU R128 integrated loudness (LUFS)
    dynamic_complexity DECIMAL(8,6),     -- Dynamic range complexity

    -- Metadata
    analyzed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    analysis_version VARCHAR(20) DEFAULT '1.0',
    error_message TEXT,                  -- Store error if analysis failed

    CONSTRAINT tracks_analyzed_track_id_unique UNIQUE (track_id)
);

-- Indexes for common queries
CREATE INDEX idx_tracks_analyzed_bpm ON tracks_analyzed(bpm) WHERE bpm IS NOT NULL;
CREATE INDEX idx_tracks_analyzed_danceability ON tracks_analyzed(danceability) WHERE danceability IS NOT NULL;
CREATE INDEX idx_tracks_analyzed_loudness ON tracks_analyzed(loudness) WHERE loudness IS NOT NULL;
CREATE INDEX idx_tracks_analyzed_analyzed_at ON tracks_analyzed(analyzed_at);

-- Comments
COMMENT ON TABLE tracks_analyzed IS 'Stores Essentia audio analysis features for ML/recommendation';
COMMENT ON COLUMN tracks_analyzed.bpm IS 'Tempo detected by Essentia RhythmExtractor2013';
COMMENT ON COLUMN tracks_analyzed.danceability IS 'Danceability score (0-1) from Essentia';
COMMENT ON COLUMN tracks_analyzed.loudness IS 'EBU R128 integrated loudness in LUFS';
COMMENT ON COLUMN tracks_analyzed.error_message IS 'Stores error message if analysis failed, NULL if successful';
