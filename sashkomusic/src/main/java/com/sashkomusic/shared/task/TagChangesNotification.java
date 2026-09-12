package com.sashkomusic.shared.task;

import com.sashkomusic.libraryagent.domain.model.TrackTagChanges;

import java.time.LocalDateTime;
import java.util.List;

public record TagChangesNotification(
        List<TrackChanges> tracks,
        int totalChanges,
        LocalDateTime timestamp
) {

    public record TrackChanges(
            Long trackId,
            String trackTitle,
            String artistName,
            List<TagChangeInfo> changes
    ) {}

    public record TagChangeInfo(
            String tagName,
            String oldValue,
            String newValue,
            boolean isNew
    ) {}

    public static TagChangesNotification create(List<TrackTagChanges> trackChanges) {
        List<TrackChanges> tracks = trackChanges.stream()
                .filter(TrackTagChanges::hasChanges)
                .map(tc -> new TrackChanges(
                        tc.getTrackId(),
                        tc.getTrackTitle(),
                        tc.getArtistName(),
                        tc.getChanges().stream()
                                .map(change -> new TagChangeInfo(
                                        change.tagName(),
                                        change.oldValue(),
                                        change.newValue(),
                                        change.isNewTag()
                                ))
                                .toList()
                ))
                .toList();

        int totalChanges = tracks.stream()
                .mapToInt(t -> t.changes().size())
                .sum();

        return new TagChangesNotification(tracks, totalChanges, LocalDateTime.now());
    }
}
