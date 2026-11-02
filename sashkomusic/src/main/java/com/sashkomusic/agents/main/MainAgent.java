package com.sashkomusic.agents.main;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface MainAgent {

    /** {@code turn} is {@code AgentTurn.parameters()} — how the tools reach the turn they answer. */
    @SystemMessage(MainAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage, InvocationParameters turn);
}
