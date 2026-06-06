package com.sashkomusic.events;

public record MoveReleaseCompleteEvent(
        String conversationId,
        Long releaseId,
        String releaseTitle,
        String releaseArtist,
        String oldPath,
        String newPath,
        String targetSublibrary,
        boolean success,
        String message
) {}
