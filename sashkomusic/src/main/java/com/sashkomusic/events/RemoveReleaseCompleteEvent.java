package com.sashkomusic.events;

public record RemoveReleaseCompleteEvent(
        String conversationId,
        Long releaseId,
        String releaseTitle,
        String directoryPath,
        String trashPath,
        boolean success,
        String message
) {}
