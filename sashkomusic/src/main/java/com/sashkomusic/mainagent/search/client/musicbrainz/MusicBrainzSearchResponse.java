package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MusicBrainzSearchResponse(
        String created,
        int count,
        int offset,
        List<Release> releases

) {

    public record Release(
            String id,
            int score,

            @JsonProperty("status-id")
            String statusId,

            @JsonProperty("packaging-id")
            String packagingId,

            @JsonProperty("artist-credit-id")
            String artistCreditId,

            int count,
            String title,
            String status,
            String disambiguation,
            String packaging,

            @JsonProperty("text-representation")
            TextRepresentation textRepresentation,

            @JsonProperty("artist-credit")
            List<ArtistCredit> artistCredit,

            @JsonProperty("release-group")
            ReleaseGroup releaseGroup,

            String date,
            String country,

            @JsonProperty("release-events")
            List<ReleaseEvent> releaseEvents,

            String barcode,
            String asin,

            @JsonProperty("label-info")
            List<LabelInfo> labelInfo,

            @JsonProperty("track-count")
            Integer trackCount,

            List<Media> media,
            List<Tag> tags
    ) {
    }

    public record TextRepresentation(
            String language,
            String script
    ) {
    }

    public record ArtistCredit(
            String name,
            Artist artist,
            String joinphrase
    ) {
    }

    public record Artist(
            String id,
            String name,

            @JsonProperty("sort-name")
            String sortName,

            String disambiguation
    ) {
    }

    public record ReleaseGroup(
            String id,

            @JsonProperty("type-id")
            String typeId,

            @JsonProperty("primary-type-id")
            String primaryTypeId,

            String title,

            @JsonProperty("primary-type")
            String primaryType,

            @JsonProperty("secondary-types")
            List<String> secondaryTypes,

            @JsonProperty("secondary-type-ids")
            List<String> secondaryTypeIds
    ) {
    }

    public record ReleaseEvent(
            String date,
            Area area
    ) {
    }

    public record Area(
            String id,
            String name,

            @JsonProperty("sort-name")
            String sortName,

            @JsonProperty("iso-3166-1-codes")
            List<String> iso31661Codes
    ) {
    }

    public record LabelInfo(
            @JsonProperty("catalog-number")
            String catalogNumber,

            Label label
    ) {
    }

    public record Label(
            String id,
            String name
    ) {
    }

    public record Media(
            String id,
            String format,

            @JsonProperty("disc-count")
            Integer discCount,

            @JsonProperty("track-count")
            Integer trackCount,

            List<Track> tracks
    ) {
    }

    public record Track(
            String id,
            String title,
            int position,
            Recording recording
    ) {
    }

    public record Recording(
            String id,
            String title,
            @JsonProperty("artist-credit")
            List<ArtistCredit> artistCredit
    ) {
    }

    public record Tag(
            int count,
            String name
    ) {
    }
}
