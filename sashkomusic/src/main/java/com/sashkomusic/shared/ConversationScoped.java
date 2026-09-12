package com.sashkomusic.shared;

/** An event addressed to one conversation. chatId is the numeric prefix of "chatId:topicId". */
public interface ConversationScoped {

    String conversationId();

    default long chatId() {
        String id = conversationId();
        int colon = id.indexOf(':');
        return Long.parseLong(colon < 0 ? id : id.substring(0, colon));
    }
}
