package com.sashkomusic.agents.main;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface MainAgent {

    @SystemMessage(MainAgentPrompts.SYSTEM)
    String chat(@MemoryId long chatId, @UserMessage String userMessage);
}
