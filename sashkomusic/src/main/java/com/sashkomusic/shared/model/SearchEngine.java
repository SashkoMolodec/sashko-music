package com.sashkomusic.shared.model;

public enum SearchEngine {
    MUSICBRAINZ,
    DISCOGS,
    BANDCAMP;

    public String getName() {
        return name().toLowerCase();
    }
}
