package com.sashkomusic.events;

/**
 * Fired when the user hits the panic button ("стоп") — every per-conversation cache and
 * session holder should drop state for {@code conversationId}. A {@link ChatContextClearedEvent}
 * is published alongside this, so listeners that already react to a soft clear do not need to
 * subscribe again.
 *
 * <p>Listeners MUST be synchronous; the orchestrator's response is gated on completion.
 */
public record ChatHardResetEvent(String conversationId) {}
