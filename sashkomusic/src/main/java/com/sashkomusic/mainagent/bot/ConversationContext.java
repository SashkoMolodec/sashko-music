package com.sashkomusic.mainagent.bot;

/**
 * Identifies a single conversation — either a DM or a specific topic in a group.
 * Used as the key for chat state, LangChain4j memory, and outgoing message routing.
 * topicId == 0 means no topic (DM or General).
 */
public record ConversationContext(long chatId, int topicId) {

    public String conversationId() {
        return topicId == 0 ? String.valueOf(chatId) : chatId + ":" + topicId;
    }

    public boolean isGroupTopic() {
        return topicId != 0;
    }

    public static ConversationContext dm(long chatId) {
        return new ConversationContext(chatId, 0);
    }

    public static ConversationContext topic(long chatId, int topicId) {
        return new ConversationContext(chatId, topicId);
    }

    public static ConversationContext from(String conversationId) {
        int colon = conversationId.indexOf(':');
        if (colon < 0) {
            return dm(Long.parseLong(conversationId));
        }
        return topic(Long.parseLong(conversationId.substring(0, colon)),
                Integer.parseInt(conversationId.substring(colon + 1)));
    }
}
