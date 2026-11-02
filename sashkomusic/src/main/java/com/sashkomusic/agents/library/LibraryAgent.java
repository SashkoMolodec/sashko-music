package com.sashkomusic.agents.library;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface LibraryAgent {

    /** {@code memoryId} is {@link LibraryAgentService#memoryId}; {@code turn} is {@code AgentTurn.parameters()}. */
    @SystemMessage(LibraryAgentPrompts.SYSTEM)
    String chat(@MemoryId String memoryId, @UserMessage String userMessage, InvocationParameters turn);
}
