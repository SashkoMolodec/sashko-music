package com.sashkomusic.libraryagent.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "track_tags",
        uniqueConstraints = @UniqueConstraint(columnNames = {"track_id", "tag_name"}))
@Getter
@Setter
public class TrackTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    @Column(name = "tag_name", nullable = false, length = 100)
    private String tagName;

    @Column(name = "tag_value", nullable = false, columnDefinition = "TEXT")
    private String tagValue;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    public TrackTag() {
    }

    public TrackTag(Track track, String tagName, String tagValue) {
        this.track = track;
        this.tagName = tagName;
        this.tagValue = tagValue;
        this.lastSyncedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrackTag)) return false;
        TrackTag trackTag = (TrackTag) o;
        return id != null && id.equals(trackTag.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
