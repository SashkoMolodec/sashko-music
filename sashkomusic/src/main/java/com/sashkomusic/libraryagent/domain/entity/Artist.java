package com.sashkomusic.libraryagent.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "artists")
@Getter
@Setter
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToMany(mappedBy = "artists")
    private Set<Release> releases = new HashSet<>();

    @ManyToMany(mappedBy = "artists")
    private Set<Track> tracks = new HashSet<>();

    public Artist() {
    }

    public Artist(String name) {
        this.name = name;
    }
}
