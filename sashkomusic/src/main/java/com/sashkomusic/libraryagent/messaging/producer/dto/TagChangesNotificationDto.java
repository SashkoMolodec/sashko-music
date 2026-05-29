package com.sashkomusic.libraryagent.messaging.producer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

public record TagChangesNotificationDto(
        @JsonProperty("tracks")
        List<TrackChanges> tracks,

        @JsonProperty("total_changes")
        int totalChanges,

        @JsonProperty("timestamp")
        LocalDateTime timestamp
) {
    public record TrackChanges(
            @JsonProperty("track_id")
            Long trackId,

            @JsonProperty("track_title")
            String trackTitle,

            @JsonProperty("artist_name")
            String artistName,

            @JsonProperty("changes")
            List<TagChangeInfo> changes
    ) {}

    public record TagChangeInfo(
            @JsonProperty("tag_name")
            String tagName,

            @JsonProperty("old_value")
            String oldValue,

            @JsonProperty("new_value")
            String newValue,

            @JsonProperty("is_new")
            boolean isNew
    ) {}

    public static TagChangesNotificationDto create(
            List<com.sashkomusic.libraryagent.domain.model.TrackTagChanges> trackChanges
    ) {
        List<TrackChanges> tracks = trackChanges.stream()
                .filter(com.sashkomusic.libraryagent.domain.model.TrackTagChanges::hasChanges)
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

        return new TagChangesNotificationDto(tracks, totalChanges, LocalDateTime.now());
    }
}
