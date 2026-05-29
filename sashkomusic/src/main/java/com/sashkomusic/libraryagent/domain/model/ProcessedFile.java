package com.sashkomusic.libraryagent.domain.model;

public record ProcessedFile(
        String originalPath,
        String newPath,
        String trackTitle,
        String trackArtist,
        int trackNumber
) {
}