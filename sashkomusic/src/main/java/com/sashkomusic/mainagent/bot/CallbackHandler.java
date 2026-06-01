package com.sashkomusic.mainagent.bot;

import java.util.List;

@FunctionalInterface
public interface CallbackHandler {
    List<BotResponse> handle(ConversationContext ctx, String data, Integer messageId);
}
