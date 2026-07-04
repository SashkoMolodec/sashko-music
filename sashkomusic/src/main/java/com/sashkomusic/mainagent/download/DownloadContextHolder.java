package com.sashkomusic.mainagent.download;

import com.sashkomusic.events.ChatHardResetEvent;
import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadContextHolder {

    private static final String FLOW_KEY = "dl_ctx";
    static final int PAGE_SIZE = 10;

    private final ChatStateStore stateStore;

    public void saveDownloadOptions(String conversationId, String releaseId,
                                    List<DownloadFlowHandler.OptionReport> currentPage,
                                    List<DownloadFlowHandler.OptionReport> allReports,
                                    DownloadEngine source) {
        log.debug("Saving download options for conversation: {}, releaseId: {}, total: {}", conversationId, releaseId, allReports.size());
        stateStore.put(conversationId, FLOW_KEY, new DownloadContext(releaseId, currentPage, allReports, 0, source));
    }

    public List<DownloadFlowHandler.OptionReport> getDownloadOptions(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::optionReports)
                .orElse(List.of());
    }

    public boolean hasNextPage(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(ctx -> (ctx.currentPage() + 1) * PAGE_SIZE < ctx.allReports().size())
                .orElse(false);
    }

    public int getTotalCount(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(ctx -> ctx.allReports().size())
                .orElse(0);
    }

    public int getCurrentPage(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::currentPage)
                .orElse(0);
    }

    public List<DownloadFlowHandler.OptionReport> advancePage(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class).map(ctx -> {
            int nextPage = ctx.currentPage() + 1;
            int from = nextPage * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, ctx.allReports().size());
            List<DownloadFlowHandler.OptionReport> next = ctx.allReports().subList(from, to);
            stateStore.put(conversationId, FLOW_KEY,
                    new DownloadContext(ctx.chosenReleaseId(), next, ctx.allReports(), nextPage, ctx.source()));
            return next;
        }).orElse(List.of());
    }

    public String getChosenRelease(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::chosenReleaseId)
                .orElse(null);
    }

    public DownloadEngine getSource(String conversationId) {
        return stateStore.get(conversationId, FLOW_KEY, DownloadContext.class)
                .map(DownloadContext::source)
                .orElse(null);
    }

    public void clearSession(String conversationId) {
        log.debug("Clearing download session for conversation: {}", conversationId);
        stateStore.remove(conversationId, FLOW_KEY);
    }

    public void clearAllSessions() {
        int count = stateStore.clearAll(FLOW_KEY);
        log.info("Cleared all download sessions: {} sessions", count);
    }

    @EventListener
    public void onHardReset(ChatHardResetEvent event) {
        clearSession(event.conversationId());
    }

    public record DownloadContext(
            String chosenReleaseId,
            List<DownloadFlowHandler.OptionReport> optionReports,
            List<DownloadFlowHandler.OptionReport> allReports,
            int currentPage,
            DownloadEngine source
    ) {}
}
