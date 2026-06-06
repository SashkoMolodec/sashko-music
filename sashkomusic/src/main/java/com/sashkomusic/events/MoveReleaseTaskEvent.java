package com.sashkomusic.events;

public record MoveReleaseTaskEvent(String conversationId, Long releaseId, String targetSublibrary) {}
