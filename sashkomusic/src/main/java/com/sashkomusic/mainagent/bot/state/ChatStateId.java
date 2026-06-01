package com.sashkomusic.mainagent.bot.state;

import java.io.Serializable;
import java.util.Objects;

public class ChatStateId implements Serializable {

    private String conversationId;
    private String flowKey;

    public ChatStateId() {}

    public ChatStateId(String conversationId, String flowKey) {
        this.conversationId = conversationId;
        this.flowKey = flowKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatStateId that)) return false;
        return Objects.equals(conversationId, that.conversationId) && Objects.equals(flowKey, that.flowKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, flowKey);
    }
}
