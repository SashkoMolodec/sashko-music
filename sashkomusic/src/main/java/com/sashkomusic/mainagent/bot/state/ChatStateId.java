package com.sashkomusic.mainagent.bot.state;

import java.io.Serializable;
import java.util.Objects;

public class ChatStateId implements Serializable {

    private long chatId;
    private String flowKey;

    public ChatStateId() {}

    public ChatStateId(long chatId, String flowKey) {
        this.chatId = chatId;
        this.flowKey = flowKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatStateId that)) return false;
        return chatId == that.chatId && Objects.equals(flowKey, that.flowKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatId, flowKey);
    }
}
