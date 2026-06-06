package com.sashkomusic.agents.library;

import com.sashkomusic.agents.contract.LibraryRequest;
import com.sashkomusic.agents.contract.LibraryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryAgentService {

    private final LibraryAgent libraryAgent;

    public LibraryResult handle(LibraryRequest request) {
        log.info("Library agent handling: conversationId={}, command='{}'",
                request.conversationId(), request.naturalCommand());
        try {
            String libraryMemoryId = request.conversationId() + ":lib";
            String summary = libraryAgent.chat(libraryMemoryId, request.naturalCommand());
            return LibraryResult.ok(summary != null && !summary.isBlank() ? summary : "готово");
        } catch (Exception ex) {
            log.error("Library agent failure for conversation {}: {}",
                    request.conversationId(), ex.getMessage(), ex);
            return LibraryResult.failed("шось накрилось: " + ex.getMessage());
        }
    }
}
