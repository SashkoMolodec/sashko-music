package com.sashkomusic.libraryagent.domain.model;

import java.util.ArrayList;
import java.util.List;

public class TrackTagChanges {
    private final Long trackId;
    private final String trackTitle;
    private final String artistName;
    private final List<TagChange> changes;

    public TrackTagChanges(Long trackId, String trackTitle, String artistName) {
        this.trackId = trackId;
        this.trackTitle = trackTitle;
        this.artistName = artistName;
        this.changes = new ArrayList<>();
    }

    public void addChange(TagChange change) {
        changes.add(change);
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public Long getTrackId() {
        return trackId;
    }

    public String getTrackTitle() {
        return trackTitle;
    }

    public String getArtistName() {
        return artistName;
    }

    public List<TagChange> getChanges() {
        return changes;
    }
}
