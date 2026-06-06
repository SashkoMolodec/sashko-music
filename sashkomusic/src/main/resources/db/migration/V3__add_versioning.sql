-- Add versioning columns to releases table
ALTER TABLE releases
ADD COLUMN metadata_version INTEGER DEFAULT 1,
ADD COLUMN last_processed TIMESTAMP DEFAULT NOW();

-- Create index for efficient version queries
CREATE INDEX idx_releases_metadata_version ON releases(metadata_version);

-- Add comment
COMMENT ON COLUMN releases.metadata_version IS 'Version of metadata processing (1, 2, 3, etc.)';
COMMENT ON COLUMN releases.last_processed IS 'Timestamp of last metadata processing';
