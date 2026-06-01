package com.sashkomusic.agents.contract;

public record LibraryRequest(
        String conversationId,
        String naturalCommand
) implements AgentRequest {

    public long chatId() {
        int colon = conversationId.indexOf(':');
        return Long.parseLong(colon < 0 ? conversationId : conversationId.substring(0, colon));
    }

    public static LibraryRequest of(String conversationId, String naturalCommand) {
        return new LibraryRequest(conversationId, naturalCommand);
    }
}
