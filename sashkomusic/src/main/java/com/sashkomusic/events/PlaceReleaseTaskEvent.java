package com.sashkomusic.events;

/** The user kept a freshly processed release in the sub-library it already lies in. */
public record PlaceReleaseTaskEvent(String conversationId, Long releaseId) {}
