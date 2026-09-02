package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import com.sashkomusic.mainagent.download.SoulseekCustomSearchFlowService;
import com.sashkomusic.mainagent.download.SoulseekDirectoryPreviewFlowService;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingAlbumFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import com.sashkomusic.mainagent.library.MarkersFlowService;
import com.sashkomusic.mainagent.library.SmartlistLabelFlowService;
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
                              SoulseekDirectoryPreviewFlowService soulseekDirPreview,
                              ProcessFolderFlowService processFolder,
                              StreamingFlowService streaming,
                              NowPlayingFlowService nowPlaying,
                              DjTagFlowService djTag,
                              RemoveReleaseFlowService removeRelease,
                              SublibraryAssignmentHandler sublibAssignment,
                              PendingProcessCallbackHandler pendingProcess,
                              SmartlistCreationFlowService smartlistCreation,
                              NowPlayingAlbumFlowService npAlbum,
                              SmartlistLabelFlowService smartlistLabel,
                              MarkersFlowService markersFlow) {
        handlers.put("CARD:", search::handleCardCallback);
        handlers.put("DIG_DEEPER", (ctx, data, msgId) -> search.switchStrategyAndSearch(ctx));
        handlers.put("NOOP", (ctx, data, msgId) -> List.of());
        handlers.put("DLOPT:", (ctx, data, msgId) -> download.handleDownloadOptionCallback(ctx, data));
        handlers.put("DLNEXT:", (ctx, data, msgId) -> download.handleNextPage(ctx, data));
        handlers.put("SEARCH_ALT:", (ctx, data, msgId) -> download.handleSearchAlternative(ctx, data));
        handlers.put("SLSK_CUSTOM:", (ctx, data, msgId) -> soulseekCustomSearch.handleCallback(ctx, data));
        handlers.put("SLSK_DIR_OK", (ctx, data, msgId) -> soulseekDirPreview.handleConfirm(ctx));
        handlers.put("SLSK_DIR_SEL", (ctx, data, msgId) -> soulseekDirPreview.promptSelection(ctx));
        handlers.put("SLSK_DIR_NO", (ctx, data, msgId) -> soulseekDirPreview.handleCancel(ctx));
        handlers.put("DL:", (ctx, data, msgId) -> download.handleDownload(ctx, data));
        handlers.put("STREAM:", (ctx, data, msgId) -> streaming.handleStreamingPlatforms(ctx, data));
        handlers.put("RATE:", (ctx, data, msgId) -> nowPlaying.handleRate(ctx, data));
        handlers.put("EXPAND_DJ_RATE:", (ctx, data, msgId) -> djTag.expandDjRatePanel(ctx, data));
        handlers.put("ENERGY_RATE:", (ctx, data, msgId) -> djTag.handleEnergyRate(ctx, data));
        handlers.put("FUNCTION_RATE:", (ctx, data, msgId) -> djTag.handleFunctionRate(ctx, data));
        handlers.put("ADD_COMMENT:", (ctx, data, msgId) -> djTag.handleCommentAdd(ctx, data));
        handlers.put("COMMENT_CANCEL:", (ctx, data, msgId) -> djTag.handleCommentCancel(ctx));
        handlers.put("RM_OK:", (ctx, data, msgId) -> removeRelease.handleConfirm(ctx, data));
        handlers.put("RM_SEL:", (ctx, data, msgId) -> removeRelease.promptTrackSelection(ctx, data));
        handlers.put("RM_NO:", (ctx, data, msgId) -> removeRelease.handleCancel(ctx, data));
        handlers.put("LIB_ASSIGN:", (ctx, data, msgId) -> sublibAssignment.handle(ctx, data));
        handlers.put("PROC_SEL:", (ctx, data, msgId) -> processFolder.handleMetadataSelectionByIndex(ctx, data));
        handlers.put("PROC_OK:", (ctx, data, msgId) -> pendingProcess.handleConfirm(ctx, data));
        handlers.put("PROC_NO:", (ctx, data, msgId) -> pendingProcess.handleCancel(ctx, data));
        handlers.put("SM:OK", (ctx, data, msgId) -> smartlistCreation.handleConfirm(ctx, data));
        handlers.put("SM:NO", (ctx, data, msgId) -> smartlistCreation.handleCancel(ctx, data));
        handlers.put("ALB_INFO:", (ctx, data, msgId) -> npAlbum.handleInfo(ctx, Long.parseLong(data.substring("ALB_INFO:".length()))));
        handlers.put("ALB_COMMENT:", (ctx, data, msgId) -> npAlbum.handleComment(ctx, Long.parseLong(data.substring("ALB_COMMENT:".length()))));
        handlers.put("ALB_RM:", (ctx, data, msgId) -> npAlbum.handleDelete(ctx, Long.parseLong(data.substring("ALB_RM:".length()))));
        handlers.put("EDIT_COMMENT:", (ctx, data, msgId) -> {
            String suffix = data.substring("EDIT_COMMENT:".length());
            String[] parts = suffix.split(":", 2);
            long targetId = Long.parseLong(parts[1]);
            return "T".equals(parts[0])
                    ? djTag.handleEditCommentTrack(ctx, targetId)
                    : npAlbum.handleEditCommentAlbum(ctx, targetId);
        });
        handlers.put("MARKERS_ADD", (ctx, data, msgId) -> markersFlow.promptCreate(ctx));
        handlers.put("MARKERS_RM", (ctx, data, msgId) -> markersFlow.promptRemove(ctx));
        handlers.put("LBL_LIST:", (ctx, data, msgId) -> smartlistLabel.showListFromCallback(ctx, data.substring("LBL_LIST:".length())));
        handlers.put("LBL_PAGE:", (ctx, data, msgId) -> smartlistLabel.goToPage(ctx, Integer.parseInt(data.substring("LBL_PAGE:".length()))));
        handlers.put("LBL_SEL:", (ctx, data, msgId) -> smartlistLabel.select(ctx, Integer.parseInt(data.substring("LBL_SEL:".length()))));
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
