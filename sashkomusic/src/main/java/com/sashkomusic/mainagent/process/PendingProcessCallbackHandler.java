package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingProcessCallbackHandler {

    private final PendingProcessContextHolder holder;
    private final ProcessFolderFlowService processFolderFlowService;

    public List<BotResponse> handleConfirm(ConversationContext ctx, String data) {
        var pending = holder.get(ctx.conversationId());
        if (pending.isEmpty()) {
            return List.of(BotResponse.text("❌ нема pending папки — спробуй ще раз"));
        }
        String folderPath = pending.get().folderPath();
        holder.clear(ctx.conversationId());
        log.info("User confirmed processing: conversationId={}, folder={}", ctx.conversationId(), folderPath);
        return processFolderFlowService.process(ctx, folderPath);
    }

    public List<BotResponse> handleCancel(ConversationContext ctx, String data) {
        holder.clear(ctx.conversationId());
        return List.of(BotResponse.text("✅ скасовано"));
    }
}
