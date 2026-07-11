package com.sashkomusic.events;

/**
 * Published by {@code SmartlistService} when a smartlist is deleted.
 * Carries the name so {@code NavidromePlaylistDeleteListener} can remove
 * the matching playlist from Navidrome via the Subsonic API.
 */
public record SmartlistDeletedEvent(String name) {}
