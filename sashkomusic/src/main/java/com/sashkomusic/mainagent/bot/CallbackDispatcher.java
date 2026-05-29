package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.streaming.StreamingFlowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CallbackDispatcher {

    private final Map<String, CallbackHandler> handlers = new LinkedHashMap<>();

    public CallbackDispatcher(ReleaseSearchFlowService search,
                              MusicDownloadFlowService download,
                              StreamingFlowService streaming,
                              NowPlayingFlowService nowPlaying,
                              DjTagFlowService djTag) {
        handlers.put("PAGE:", search::handlePageCallback);
        handlers.put("DIG_DEEPER", (chatId, data) -> search.switchStrategyAndSearch(chatId));
        handlers.put("CANCEL_DL:", download::handleDownloadCancel);
        handlers.put("SEARCH_ALT:", download::handleSearchAlternative);
        handlers.put("DL:", download::handleDownload);
        handlers.put("STREAM:", streaming::handleStreamingPlatforms);
        handlers.put("RATE:", nowPlaying::handleRate);
        handlers.put("EXPAND_DJ_RATE:", djTag::expandDjRatePanel);
        handlers.put("ENERGY_RATE:", djTag::handleEnergyRate);
        handlers.put("FUNCTION_RATE:", djTag::handleFunctionRate);
        handlers.put("ADD_COMMENT:", djTag::handleCommentAdd);
    }

    public List<BotResponse> dispatch(long chatId, String data) {
        for (var entry : handlers.entrySet()) {
            if (data.startsWith(entry.getKey())) {
                return entry.getValue().handle(chatId, data);
            }
        }
        log.warn("No callback handler matched: {}", data);
        return List.of(BotResponse.text("хз, пупупу"));
    }
}
