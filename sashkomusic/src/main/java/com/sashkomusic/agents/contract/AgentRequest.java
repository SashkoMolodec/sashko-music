package com.sashkomusic.agents.contract;

public sealed interface AgentRequest permits DiscoverRequest, DownloadRequest, LibraryRequest {
    long chatId();
}
