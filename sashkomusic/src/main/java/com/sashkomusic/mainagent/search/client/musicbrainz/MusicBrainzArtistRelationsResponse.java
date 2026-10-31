package com.sashkomusic.mainagent.search.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response shape for GET /artist/{mbid}?inc=artist-rels — who an artist really is: the people behind
 * a group, the aliases of a person and the bands they played in. {@code direction} reads the relation
 * from this artist's side: a person's alias is "is person" forward, a group's member is
 * "member of band" backward.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MusicBrainzArtistRelationsResponse(
        String id,
        String name,
        String type,
        List<Relation> relations
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(
            String type,
            String direction,
            Artist artist
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(
            String id,
            String name,
            String type
    ) {
    }
}
