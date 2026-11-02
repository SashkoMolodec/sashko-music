package com.sashkomusic.agents.library;

import com.sashkomusic.agents.turn.AgentTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryAgentService {

    private static final String MEMORY_SUFFIX = ":lib";

    private final LibraryAgent libraryAgent;

    /**
     * LibraryAgent's own memory key. It cannot share MainAgent's: the two agents' tool_use/tool_result
     * pairs would interleave, and the Claude API rejects a history with an unanswered tool_use.
     */
    public static String memoryId(String conversationId) {
        return conversationId + MEMORY_SUFFIX;
    }

    /** Runs one library command in {@code turn}; the reply is a status line for MainAgent, never null. */
    public String handle(AgentTurn turn, String command) {
        log.info("Library agent handling: conversationId={}, command='{}'", turn.conversationId(), command);
        try {
            String summary = libraryAgent.chat(memoryId(turn.conversationId()), command, turn.parameters());
            return summary != null && !summary.isBlank() ? summary : "готово";
        } catch (Exception ex) {
            log.error("Library agent failure for conversation {}: {}", turn.conversationId(), ex.getMessage(), ex);
            return "шось накрилось: " + ex.getMessage();
        }
    }
}
