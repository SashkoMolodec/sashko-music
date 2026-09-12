package com.sashkomusic.events;

import com.sashkomusic.shared.ConversationScoped;
import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;

import java.util.List;

public record FileSearchResultEvent(
        String conversationId,
        String releaseId,
        DownloadEngine source,
        List<DownloadOption> results
) implements ConversationScoped {}
