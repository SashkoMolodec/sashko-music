package com.sashkomusic.libraryagent.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tracks")
@Getter
@Setter
public class Track {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private Integer trackNumber;

    @Column
    private Integer duration; // in seconds

    @Column
    private String localPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false)
    private Release release;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "track_artists",
            joinColumns = @JoinColumn(name = "track_id"),
            inverseJoinColumns = @JoinColumn(name = "artist_id")
    )
    private Set<Artist> artists = new HashSet<>();

    @OneToMany(mappedBy = "track", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TrackTag> tags = new HashSet<>();

    public Track() {
    }

    public Track(String title, Integer trackNumber) {
        this.title = title;
        this.trackNumber = trackNumber;
    }

    public void addArtist(Artist artist) {
        artists.add(artist);
        artist.getTracks().add(this);
    }

    public void removeArtist(Artist artist) {
        artists.remove(artist);
        artist.getTracks().remove(this);
    }

    public void setTag(String tagName, String tagValue) {
        TrackTag tag = tags.stream()
                .filter(t -> t.getTagName().equals(tagName))
                .findFirst()
                .orElseGet(() -> {
                    TrackTag newTag = new TrackTag(this, tagName, tagValue);
                    tags.add(newTag);
                    return newTag;
                });
        tag.setTagValue(tagValue);
        tag.setLastSyncedAt(java.time.LocalDateTime.now());
    }

    public void prependToTag(String tagName, String newValue) {
        TrackTag tag = tags.stream()
                .filter(t -> t.getTagName().equals(tagName))
                .findFirst()
                .orElse(null);

        if (tag == null) {
            setTag(tagName, newValue);
        } else {
            String existingValue = tag.getTagValue();
            String combined = newValue + "; " + existingValue;
            tag.setTagValue(combined);
            tag.setLastSyncedAt(java.time.LocalDateTime.now());
        }
    }

    public java.util.Optional<String> getTag(String tagName) {
        return tags.stream()
                .filter(t -> t.getTagName().equals(tagName))
                .findFirst()
                .map(TrackTag::getTagValue);
    }

    public void removeTag(String tagName) {
        tags.removeIf(t -> t.getTagName().equals(tagName));
    }

    public boolean hasTag(String tagName) {
        return tags.stream().anyMatch(t -> t.getTagName().equals(tagName));
    }
}
