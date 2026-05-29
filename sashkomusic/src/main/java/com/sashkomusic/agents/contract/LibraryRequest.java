package com.sashkomusic.agents.contract;

public record LibraryRequest(
        long chatId,
        String naturalCommand
) implements AgentRequest {

    public static LibraryRequest of(long chatId, String naturalCommand) {
        return new LibraryRequest(chatId, naturalCommand);
    }
}
