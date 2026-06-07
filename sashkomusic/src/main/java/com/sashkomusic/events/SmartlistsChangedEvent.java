package com.sashkomusic.events;

/**
 * Signal that the set of smartlists or their content on disk has changed.
 * Published by {@code SmartlistService} on create / delete / rename / regenerate.
 * Used by {@code NavidromeSmartlistScanListener} to trigger a Navidrome rescan
 * of the Smartlists folder so playlist deletions / additions propagate without
 * waiting for Navidrome's scheduled scan.
 */
public record SmartlistsChangedEvent() {}
