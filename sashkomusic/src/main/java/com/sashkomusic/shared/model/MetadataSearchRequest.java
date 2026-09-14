package com.sashkomusic.shared.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * True when the caller has no specific release/recording title to look up — only
     * style/year/label-type filters ("trance 1994", "German techno 90s"). Used to route
     * to browse-friendly search strategies (MusicBrainz release-group, Discogs structured
     * genre/style/year params) instead of title-lookup ones.
     */
    public boolean isBrowseQuery() {
        return release.isEmpty() && recording.isEmpty()
                && (!style.isEmpty() || (dateRange != null && !dateRange.isEmpty()));
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

    /** Same query without the artist constraint — for lookups that must reach compilations, where
     * the requested artist appears on a track rather than as the release's album artist. */
    public MetadataSearchRequest withoutArtist() {
        return new MetadataSearchRequest(
                this.id, "", this.release, this.recording, this.dateRange, this.format,
                this.type, this.country, this.status, this.style, this.label, this.catno);
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
