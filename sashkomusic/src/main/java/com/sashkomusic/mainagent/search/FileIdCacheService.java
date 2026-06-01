package com.sashkomusic.mainagent.search;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileIdCacheService {

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public void put(String conversationId, String imageUrl, String fileId) {
        cache.put(conversationId + "|" + imageUrl, fileId);
    }

    public Optional<String> get(String conversationId, String imageUrl) {
        return Optional.ofNullable(cache.get(conversationId + "|" + imageUrl));
    }

    public void clearForConversation(String conversationId) {
        String prefix = conversationId + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
