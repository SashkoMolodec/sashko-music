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
        handlers.put("CARD:", search::handleCardCallback);
        handlers.put("DIG_DEEPER", (ctx, data, msgId) -> search.switchStrategyAndSearch(ctx));
        handlers.put("NOOP", (ctx, data, msgId) -> List.of());
        handlers.put("CANCEL_DL:", (ctx, data, msgId) -> download.handleDownloadCancel(ctx, data));
        handlers.put("SEARCH_ALT:", (ctx, data, msgId) -> download.handleSearchAlternative(ctx, data));
        handlers.put("DL:", (ctx, data, msgId) -> download.handleDownload(ctx, data));
        handlers.put("STREAM:", (ctx, data, msgId) -> streaming.handleStreamingPlatforms(ctx, data));
        handlers.put("RATE:", (ctx, data, msgId) -> nowPlaying.handleRate(ctx, data));
        handlers.put("EXPAND_DJ_RATE:", (ctx, data, msgId) -> djTag.expandDjRatePanel(ctx, data));
        handlers.put("ENERGY_RATE:", (ctx, data, msgId) -> djTag.handleEnergyRate(ctx, data));
        handlers.put("FUNCTION_RATE:", (ctx, data, msgId) -> djTag.handleFunctionRate(ctx, data));
        handlers.put("ADD_COMMENT:", (ctx, data, msgId) -> djTag.handleCommentAdd(ctx, data));
    }

    public List<BotResponse> dispatch(ConversationContext ctx, String data, Integer messageId) {
        for (var entry : handlers.entrySet()) {
            if (data.startsWith(entry.getKey())) {
                return entry.getValue().handle(ctx, data, messageId);
            }
        }
        log.warn("No callback handler matched: {}", data);
        return List.of(BotResponse.text("хз, пупупу"));
    }
}
