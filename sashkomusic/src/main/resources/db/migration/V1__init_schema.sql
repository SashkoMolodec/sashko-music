-- Artists table
CREATE TABLE artists (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- Tags table
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

-- Releases table
CREATE TABLE releases (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(255) NOT NULL UNIQUE,
    master_id VARCHAR(255),
    source VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    release_type VARCHAR(50),
    release_format VARCHAR(50) NOT NULL DEFAULT 'DIGITAL',
    initial_release INTEGER,
    types TEXT[],
    cover_path VARCHAR(1000),
    directory_path VARCHAR(1000) NOT NULL
);

-- Tracks table
CREATE TABLE tracks (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    track_number INTEGER,
    duration INTEGER,
    local_path VARCHAR(1000),
    release_id BIGINT NOT NULL,
    CONSTRAINT fk_track_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE
);

-- Join table: Release <-> Artist (ManyToMany)
CREATE TABLE release_artists (
    release_id BIGINT NOT NULL,
    artist_id BIGINT NOT NULL,
    PRIMARY KEY (release_id, artist_id),
    CONSTRAINT fk_release_artists_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE,
    CONSTRAINT fk_release_artists_artist FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
);

-- Join table: Release <-> Tag (ManyToMany)
CREATE TABLE release_tags (
    release_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (release_id, tag_id),
    CONSTRAINT fk_release_tags_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE,
    CONSTRAINT fk_release_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);

-- Join table: Track <-> Artist (ManyToMany)
CREATE TABLE track_artists (
    track_id BIGINT NOT NULL,
    artist_id BIGINT NOT NULL,
    PRIMARY KEY (track_id, artist_id),
    CONSTRAINT fk_track_artists_track FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE,
    CONSTRAINT fk_track_artists_artist FOREIGN KEY (artist_id) REFERENCES artists(id) ON DELETE CASCADE
);

-- Indexes for better query performance
CREATE INDEX idx_releases_source_id ON releases(source_id);
CREATE INDEX idx_releases_master_id ON releases(master_id);
CREATE INDEX idx_releases_source ON releases(source);
CREATE INDEX idx_tracks_release_id ON tracks(release_id);
CREATE INDEX idx_tags_name ON tags(name);
CREATE INDEX idx_artists_name ON artists(name);
