package com.sashkomusic.libraryagent.domain.entity;

import com.sashkomusic.libraryagent.domain.model.ReleaseFormat;
import com.sashkomusic.libraryagent.domain.model.ReleaseType;
import com.sashkomusic.mainagent.search.SearchEngine;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "releases")
@Getter
@Setter
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sourceId;

    @Column
    private String masterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SearchEngine source;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column
    private ReleaseType releaseType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReleaseFormat releaseFormat = ReleaseFormat.DIGITAL;

    @Column
    private Integer initialRelease;

    @Column(columnDefinition = "text[]")
    private String[] types;

    @Column
    private String coverPath;

    @Column(nullable = false)
    private String directoryPath;

    @Column(nullable = false)
    private String sublibrary = "working";

    @Column
    private Integer metadataVersion;

    @Column
    private LocalDateTime lastProcessed;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "release_artists",
            joinColumns = @JoinColumn(name = "release_id"),
            inverseJoinColumns = @JoinColumn(name = "artist_id")
    )
    private Set<Artist> artists = new HashSet<>();

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "release_tags",
            joinColumns = @JoinColumn(name = "release_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "label_id")
    private Label label;

    @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Track> tracks = new HashSet<>();

    public Release() {
    }

    public void addArtist(Artist artist) {
        artists.add(artist);
        artist.getReleases().add(this);
    }

    public void removeArtist(Artist artist) {
        artists.remove(artist);
        artist.getReleases().remove(this);
    }

    public void addTag(Tag tag) {
        tags.add(tag);
        tag.getReleases().add(this);
    }

    public void removeTag(Tag tag) {
        tags.remove(tag);
        tag.getReleases().remove(this);
    }

    public void addTrack(Track track) {
        tracks.add(track);
        track.setRelease(this);
    }

    public void removeTrack(Track track) {
        tracks.remove(track);
        track.setRelease(null);
    }
}
