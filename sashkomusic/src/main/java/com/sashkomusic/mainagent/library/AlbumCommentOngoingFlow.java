package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.OngoingFlow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AlbumCommentOngoingFlow implements OngoingFlow {

    private final AlbumCommentContextHolder holder;
    private final NowPlayingAlbumFlowService service;

    @Override
    public boolean appliesTo(ConversationContext ctx) {
        return holder.get(ctx.conversationId()).isPresent();
    }

    @Override
    public List<BotResponse> handle(ConversationContext ctx, String input) {
        AlbumCommentContextHolder.AlbumCommentContext albumCtx = holder.get(ctx.conversationId()).orElse(null);
        if (albumCtx != null && albumCtx.replaceMode()) {
            return service.applyReplaceComment(ctx, input);
        }
        return service.applyComment(ctx, input);
    }
}
