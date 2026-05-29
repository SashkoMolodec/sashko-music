package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AgentTraceListener implements ChatModelListener {

    private final String agentName;

    public AgentTraceListener(String agentName) {
        this.agentName = agentName;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        var req = requestContext.chatRequest();
        int messageCount = req.messages() == null ? 0 : req.messages().size();
        int toolCount = req.toolSpecifications() == null ? 0 : req.toolSpecifications().size();
        log.info("[agent={}] → request messages={} tools={}", agentName, messageCount, toolCount);
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        var resp = responseContext.chatResponse();
        AiMessage aiMessage = resp.aiMessage();
        int toolCalls = aiMessage.hasToolExecutionRequests() ? aiMessage.toolExecutionRequests().size() : 0;
        var meta = resp.metadata();
        var usage = meta == null ? null : meta.tokenUsage();
        int inputTokens = usage == null ? -1 : usage.inputTokenCount();
        int outputTokens = usage == null ? -1 : usage.outputTokenCount();
        log.info("[agent={}] ← response toolCalls={} tokensIn={} tokensOut={}",
                agentName, toolCalls, inputTokens, outputTokens);
        if (toolCalls > 0) {
            aiMessage.toolExecutionRequests().forEach(t ->
                    log.info("[agent={}]   tool={} args={}", agentName, t.name(), t.arguments()));
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.error("[agent={}] ✗ error: {}", agentName, errorContext.error().getMessage());
    }
}
