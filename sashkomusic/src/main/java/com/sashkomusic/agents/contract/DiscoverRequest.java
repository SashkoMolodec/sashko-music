package com.sashkomusic.agents.contract;

import com.sashkomusic.mainagent.search.SearchEngine;

public record DiscoverRequest(
        long chatId,
        String query,
        SearchEngine preferredEngine
) implements AgentRequest {

    public static DiscoverRequest of(long chatId, String query) {
        return new DiscoverRequest(chatId, query, null);
    }

    public static DiscoverRequest of(long chatId, String query, SearchEngine engine) {
        return new DiscoverRequest(chatId, query, engine);
    }
}
