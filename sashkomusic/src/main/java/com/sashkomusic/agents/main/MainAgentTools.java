package com.sashkomusic.agents.main;

import com.sashkomusic.agents.bridge.ProgressNotifier;
import com.sashkomusic.agents.contract.DiscoverRequest;
import com.sashkomusic.agents.contract.LibraryRequest;
import com.sashkomusic.agents.contract.LibraryResult;
import com.sashkomusic.agents.discovery.DiscoveryAgentService;
import com.sashkomusic.agents.library.LibraryAgentService;
import com.sashkomusic.mainagent.bot.ConversationContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MainAgentTools {

    private final DiscoveryAgentService discoveryAgent;
    private final LibraryAgentService libraryAgent;
    private final ProgressNotifier progressNotifier;

    @Tool("Delegate any music discovery or research request to DiscoveryAgent — searching for releases, digging deeper, asking about artists, genres, labels, history. Use for anything curiosity/exploration related.")
    public String discoverMusic(
            @P("user's full query verbatim") String query,
            @ToolMemoryId String conversationId) {
        progressNotifier.notify(ConversationContext.from(conversationId), "🔍 шукаю...");
        return discoveryAgent.handle(DiscoverRequest.of(conversationId, query)).summary();
    }

    @Tool("Delegate any operation on the user's own music library to LibraryAgent — searching it, moving releases between sub-libraries, trashing, or DJ-tagging the currently playing track.")
    public String manageLibrary(
            @P("the user's natural-language library command, verbatim") String command,
            @ToolMemoryId String conversationId) {
        LibraryResult result = libraryAgent.handle(new LibraryRequest(conversationId, command));
        return result.summary();
    }
}
