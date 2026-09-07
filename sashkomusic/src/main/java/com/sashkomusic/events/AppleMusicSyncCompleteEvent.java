package com.sashkomusic.events;

import java.util.List;

public record AppleMusicSyncCompleteEvent(String directoryPath, List<TrackDbid> tracks) {
    public record TrackDbid(String filePath, long dbid) {}
}
