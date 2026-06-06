package com.sashkomusic.agents.library;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface LibraryAgent {

    @SystemMessage(LibraryAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
