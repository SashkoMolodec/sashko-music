ALTER TABLE releases
    ADD COLUMN sublibrary VARCHAR(64) NOT NULL DEFAULT 'working';

CREATE INDEX idx_releases_sublibrary ON releases(sublibrary);
