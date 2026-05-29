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
    private final Map<Long, List<String>> chatReleaseIds = new ConcurrentHashMap<>();
    private final Map<Long, String> chatContextKeys = new ConcurrentHashMap<>();

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

    public void storeReleaseIds(long chatId, List<String> releaseIds) {
        chatReleaseIds.put(chatId, releaseIds);
    }

    public void storeChatContext(long chatId, String contextKey) {
        chatContextKeys.put(chatId, contextKey);
    }

    public String getChatContextKey(long chatId) {
        return chatContextKeys.get(chatId);
    }

    public String getReleaseIdByOption(long chatId, int optionNumber) {
        List<String> releases = chatReleaseIds.get(chatId);
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

    public void clearChatSelection(long chatId) {
        chatReleaseIds.remove(chatId);
        chatContextKeys.remove(chatId);
    }

    public void clearAllContexts() {
        int contextsCount = contexts.size();
        int releaseIdsCount = chatReleaseIds.size();
        int contextKeysCount = chatContextKeys.size();

        contexts.clear();
        chatReleaseIds.clear();
        chatContextKeys.clear();

        log.info("Cleared all process contexts: {} contexts, {} release mappings, {} chat mappings",
                contextsCount, releaseIdsCount, contextKeysCount);
    }
}