package com.sashkomusic.agents.contract;

public sealed interface AgentResponse permits DiscoverResult, DownloadResult, LibraryResult {
    boolean success();
    String summary();
}
