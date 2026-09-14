package com.sashkomusic.agents.contract;

public record DiscoverRequest(
        String conversationId,
        String query
) implements AgentRequest {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static DiscoverRequest of(String conversationId, String query) {
        return new DiscoverRequest(conversationId, query);
    }
}
