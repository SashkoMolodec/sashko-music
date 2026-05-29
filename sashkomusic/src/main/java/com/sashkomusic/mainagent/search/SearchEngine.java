package com.sashkomusic.mainagent.search;

public enum SearchEngine {
    MUSICBRAINZ,
    DISCOGS,
    BANDCAMP;

    public String getName() {
        return name().toLowerCase();
    }
}
