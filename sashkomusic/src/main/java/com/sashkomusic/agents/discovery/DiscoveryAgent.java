package com.sashkomusic.agents.discovery;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface DiscoveryAgent {

    @SystemMessage(DiscoveryAgentPrompts.SYSTEM)
    String chat(@MemoryId long chatId, @UserMessage String userMessage);
}
