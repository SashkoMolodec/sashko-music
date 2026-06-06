-- Create track_tags table for flexible tag storage
CREATE TABLE track_tags (
    id BIGSERIAL PRIMARY KEY,
    track_id BIGINT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    tag_name VARCHAR(100) NOT NULL,
    tag_value TEXT NOT NULL,
    last_synced_at TIMESTAMP,
    CONSTRAINT uk_track_tag UNIQUE (track_id, tag_name)
);

-- Index for finding all tags of a track
CREATE INDEX idx_track_tags_track_id ON track_tags(track_id);

-- Partial indexes for frequently queried tags (BPM, Key, Rating)
CREATE INDEX idx_track_tags_bpm ON track_tags(tag_name, tag_value) WHERE tag_name = 'BPM';
CREATE INDEX idx_track_tags_key ON track_tags(tag_name, tag_value) WHERE tag_name IN ('INITIALKEY', 'TKEY');
CREATE INDEX idx_track_tags_rating ON track_tags(tag_name, tag_value) WHERE tag_name = 'RATING';

-- Generic index for other tags
CREATE INDEX idx_track_tags_name_value ON track_tags(tag_name, tag_value);

-- Comment the table
COMMENT ON TABLE track_tags IS 'Stores all ID3 tags from audio files for flexible querying';
COMMENT ON COLUMN track_tags.tag_name IS 'ID3 frame name (e.g., TBPM, TKEY, TXXX:ENERGY)';
COMMENT ON COLUMN track_tags.tag_value IS 'Tag value as string';
COMMENT ON COLUMN track_tags.last_synced_at IS 'When this tag was last synchronized from the audio file';
