package com.sashkomusic.libraryagent.domain.model;

public record LibrarySearchResult(
        Long releaseId,
        String title,
        String artists,
        Integer year,
        String tags,
        String directoryPath,
        int trackCount,
        double rank
) {}
