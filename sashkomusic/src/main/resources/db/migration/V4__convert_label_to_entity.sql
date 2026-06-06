-- Drop old label VARCHAR column and index
DROP INDEX IF EXISTS idx_releases_label;
ALTER TABLE releases DROP COLUMN IF EXISTS label;

-- Create labels table
CREATE TABLE labels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

-- Add label_id foreign key to releases
ALTER TABLE releases ADD COLUMN label_id BIGINT;

-- Add foreign key constraint
ALTER TABLE releases
    ADD CONSTRAINT fk_releases_label
    FOREIGN KEY (label_id)
    REFERENCES labels(id)
    ON DELETE SET NULL;

-- Create index for label_id for faster joins
CREATE INDEX idx_releases_label_id ON releases(label_id);

-- Add comments
COMMENT ON TABLE labels IS 'Record labels (e.g., Sony Music, Warner, etc.)';
COMMENT ON COLUMN releases.label_id IS 'Foreign key to labels table';
