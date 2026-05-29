package com.sashkomusic.mainagent.shared.model;

import java.util.UUID;

public record MetadataSearchRequest(
        String id,
        String artist,
        String release,
        String recording,
        DateRange dateRange,
        String format,
        String type,
        String country,
        String status,
        String style,
        String label,
        String catno
) {

    public static MetadataSearchRequest create(
            String artist,
            String release,
            String recording,
            DateRange dateRange,
            String format,
            String type,
            String country,
            String status,
            String style,
            String label,
            String catno,
            Language language
    ) {
        return new MetadataSearchRequest(
                UUID.randomUUID().toString(),
                artist != null ? artist : "",
                release != null ? release : "",
                recording != null ? recording : "",
                dateRange != null ? dateRange : DateRange.empty(),
                format != null ? format : "",
                type != null ? type : "",
                country != null ? country : "",
                status != null ? status : "",
                style != null ? style : "",
                label != null ? label : "",
                catno != null ? catno : ""
        );
    }

    public String getTitle() {
        if (!release.isEmpty()) {
            return release;
        }
        if (!recording.isEmpty()) {
            return recording;
        }
        return "";
    }

    public MetadataSearchRequest withRelease(String newRelease) {
        return new MetadataSearchRequest(
                this.id,
                this.artist,
                newRelease,
                this.recording,
                this.dateRange,
                this.format,
                this.type,
                this.country,
                this.status,
                this.style,
                this.label,
                this.catno
        );
    }

    public MetadataSearchRequest withRecording(String newRecording) {
        return new MetadataSearchRequest(
                this.id,
                this.artist,
                this.release,
                newRecording,
                this.dateRange,
                this.format,
                this.type,
                this.country,
                this.status,
                this.style,
                this.label,
                this.catno
        );
    }

    public MetadataSearchRequest withAuthor(String author) {
        return new MetadataSearchRequest(
                this.id,
                this.artist,
                author,
                this.recording,
                this.dateRange,
                this.format,
                this.type,
                this.country,
                this.status,
                this.style,
                this.label,
                this.catno
        );
    }
}
