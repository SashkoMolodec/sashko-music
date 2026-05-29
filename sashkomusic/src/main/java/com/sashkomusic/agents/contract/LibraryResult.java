package com.sashkomusic.agents.contract;

public record LibraryResult(
        boolean success,
        String summary
) implements AgentResponse {

    public static LibraryResult ok(String summary) {
        return new LibraryResult(true, summary);
    }

    public static LibraryResult failed(String summary) {
        return new LibraryResult(false, summary);
    }
}
