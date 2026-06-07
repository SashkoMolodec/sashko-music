package com.sashkomusic.events;

/**
 * Fired when a conversation's "soft" context is being cleared (e.g. via {@code /clearctx}).
 * Subscribers should drop their per-conversation state that the user expects to evaporate
 * on context reset — chat memory windows, in-flight drafts, last-referenced markers, etc.
 *
 * <p>Listeners MUST be synchronous (no {@code @Async}): the orchestrator returns the
 * "контекст очищено" reply only after all listeners have run.
 */
public record ChatContextClearedEvent(String conversationId) {}
