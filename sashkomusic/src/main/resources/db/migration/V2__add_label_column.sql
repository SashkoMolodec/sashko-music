-- Add label column to releases table
ALTER TABLE releases ADD COLUMN label VARCHAR(255);

-- Create index for label column for faster searches
CREATE INDEX idx_releases_label ON releases(label);
