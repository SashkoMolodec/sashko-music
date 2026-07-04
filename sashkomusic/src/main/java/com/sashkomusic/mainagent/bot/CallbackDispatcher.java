package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import com.sashkomusic.mainagent.download.SoulseekCustomSearchFlowService;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.library.RemoveReleaseFlowService;
import com.sashkomusic.mainagent.library.SmartlistCreationFlowService;
import com.sashkomusic.mainagent.library.SublibraryAssignmentHandler;
import com.sashkomusic.mainagent.process.PendingProcessCallbackHandler;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
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
                              SoulseekCustomSearchFlowService soulseekCustomSearch,
                              ProcessFolderFlowService processFolder,
                              StreamingFlowService streaming,
                              NowPlayingFlowService nowPlaying,
                              DjTagFlowService djTag,
                              RemoveReleaseFlowService removeRelease,
                              SublibraryAssignmentHandler sublibAssignment,
                              PendingProcessCallbackHandler pendingProcess,
                              SmartlistCreationFlowService smartlistCreation) {
        handlers.put("CARD:", search::handleCardCallback);
        handlers.put("DIG_DEEPER", (ctx, data, msgId) -> search.switchStrategyAndSearch(ctx));
        handlers.put("NOOP", (ctx, data, msgId) -> List.of());
        handlers.put("DLOPT:", (ctx, data, msgId) -> download.handleDownloadOptionCallback(ctx, data));
        handlers.put("SEARCH_ALT:", (ctx, data, msgId) -> download.handleSearchAlternative(ctx, data));
        handlers.put("SLSK_CUSTOM:", (ctx, data, msgId) -> soulseekCustomSearch.handleCallback(ctx, data));
        handlers.put("DL:", (ctx, data, msgId) -> download.handleDownload(ctx, data));
        handlers.put("STREAM:", (ctx, data, msgId) -> streaming.handleStreamingPlatforms(ctx, data));
        handlers.put("RATE:", (ctx, data, msgId) -> nowPlaying.handleRate(ctx, data));
        handlers.put("EXPAND_DJ_RATE:", (ctx, data, msgId) -> djTag.expandDjRatePanel(ctx, data));
        handlers.put("ENERGY_RATE:", (ctx, data, msgId) -> djTag.handleEnergyRate(ctx, data));
        handlers.put("FUNCTION_RATE:", (ctx, data, msgId) -> djTag.handleFunctionRate(ctx, data));
        handlers.put("ADD_COMMENT:", (ctx, data, msgId) -> djTag.handleCommentAdd(ctx, data));
        handlers.put("RM_OK:", (ctx, data, msgId) -> removeRelease.handleConfirm(ctx, data));
        handlers.put("RM_NO:", (ctx, data, msgId) -> removeRelease.handleCancel(ctx, data));
        handlers.put("LIB_ASSIGN:", (ctx, data, msgId) -> sublibAssignment.handle(ctx, data));
        handlers.put("PROC_SEL:", (ctx, data, msgId) -> processFolder.handleMetadataSelectionByIndex(ctx, data));
        handlers.put("PROC_OK:", (ctx, data, msgId) -> pendingProcess.handleConfirm(ctx, data));
        handlers.put("PROC_NO:", (ctx, data, msgId) -> pendingProcess.handleCancel(ctx, data));
        handlers.put("SM:OK", (ctx, data, msgId) -> smartlistCreation.handleConfirm(ctx, data));
        handlers.put("SM:NO", (ctx, data, msgId) -> smartlistCreation.handleCancel(ctx, data));
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
