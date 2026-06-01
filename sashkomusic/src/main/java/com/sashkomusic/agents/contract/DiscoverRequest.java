package com.sashkomusic.agents.contract;

import com.sashkomusic.mainagent.search.SearchEngine;

public record DiscoverRequest(
        String conversationId,
        String query,
        SearchEngine preferredEngine
) implements AgentRequest {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DiscoverRequest of(String conversationId, String query) {
        return new DiscoverRequest(conversationId, query, null);
    }

    public static DiscoverRequest of(String conversationId, String query, SearchEngine engine) {
        return new DiscoverRequest(conversationId, query, engine);
    }
}
