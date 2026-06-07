package com.sashkomusic.events;

/**
 * Published by SmartlistRegenerationListener after a regeneration pass completes.
 * @param count number of smartlists successfully regenerated
 * @param total total number of smartlists attempted
 */
public record SmartlistsRegeneratedEvent(int count, int total) {}
