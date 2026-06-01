package com.sashkomusic.mainagent.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ProcessFolderContextHolder {

    private final Map<String, ProcessFolderContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, List<String>> convReleaseIds = new ConcurrentHashMap<>();
    private final Map<String, String> convContextKeys = new ConcurrentHashMap<>();

    public record ProcessFolderContext(
            String directoryPath,
            List<String> audioFiles
    ) {}

    public String generateShortKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public void store(String contextKey, String directoryPath, List<String> audioFiles) {
        contexts.put(contextKey, new ProcessFolderContext(directoryPath, audioFiles));
    }

    public void storeReleaseIds(String conversationId, List<String> releaseIds) {
        convReleaseIds.put(conversationId, releaseIds);
    }

    public void storeChatContext(String conversationId, String contextKey) {
        convContextKeys.put(conversationId, contextKey);
    }

    public String getChatContextKey(String conversationId) {
        return convContextKeys.get(conversationId);
    }

    public String getReleaseIdByOption(String conversationId, int optionNumber) {
        List<String> releases = convReleaseIds.get(conversationId);
        if (releases == null || optionNumber < 1 || optionNumber > releases.size()) {
            return null;
        }
        return releases.get(optionNumber - 1);
    }

    public ProcessFolderContext get(String contextKey) {
        return contexts.get(contextKey);
    }

    public void remove(String contextKey) {
        contexts.remove(contextKey);
    }

    public void clearChatSelection(String conversationId) {
        convReleaseIds.remove(conversationId);
        convContextKeys.remove(conversationId);
    }

    public void clearAllContexts() {
        int contextsCount = contexts.size();
        int releaseIdsCount = convReleaseIds.size();
        int contextKeysCount = convContextKeys.size();

        contexts.clear();
        convReleaseIds.clear();
        convContextKeys.clear();

        log.info("Cleared all process contexts: {} contexts, {} release mappings, {} chat mappings",
                contextsCount, releaseIdsCount, contextKeysCount);
    }
}
