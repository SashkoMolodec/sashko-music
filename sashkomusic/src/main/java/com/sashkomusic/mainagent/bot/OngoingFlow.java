package com.sashkomusic.mainagent.bot;

import java.util.List;

/**
 * A flow that may be in the middle of multi-turn dialogue with a conversation.
 * Orchestrator iterates registered {@code OngoingFlow}s before normal command / NL handling.
 * To add a new ongoing flow: implement this interface and annotate the impl with {@code @Component}.
 */
public interface OngoingFlow {

    /** Returns true when this flow currently holds pending state for this conversation. */
    boolean appliesTo(ConversationContext ctx);

    /** Handle the next user message in the ongoing dialogue. */
    List<BotResponse> handle(ConversationContext ctx, String input);
}
