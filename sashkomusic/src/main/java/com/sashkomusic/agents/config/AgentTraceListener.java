package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class AgentTraceListener implements ChatModelListener {

    private static final ConcurrentHashMap<String, Double> flowCosts = new ConcurrentHashMap<>();

    public static double drainFlowCost(String flowId) {
        if (flowId == null) return 0.0;
        Double cost = flowCosts.remove(flowId);
        return cost != null ? cost : 0.0;
    }

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
        TokenUsage usage = meta == null ? null : meta.tokenUsage();
        int inputTokens = usage == null ? 0 : usage.inputTokenCount();
        int outputTokens = usage == null ? 0 : usage.outputTokenCount();
        int cacheRead = 0;
        int cacheWrite = 0;
        if (usage instanceof AnthropicTokenUsage anthropicUsage) {
            cacheRead = anthropicUsage.cacheReadInputTokens() != null ? anthropicUsage.cacheReadInputTokens() : 0;
            cacheWrite = anthropicUsage.cacheCreationInputTokens() != null ? anthropicUsage.cacheCreationInputTokens() : 0;
        }
        double costUsd = estimateCostUsd(agentName, inputTokens, outputTokens, cacheRead, cacheWrite);
        String flowId = MDC.get("flowId");
        if (flowId != null) flowCosts.merge(flowId, costUsd, Double::sum);
        log.info("[agent={}] [flow={}] ← response toolCalls={} tokensIn={} tokensOut={} cacheRead={} cacheWrite={} cost=${}",
                agentName, flowId, toolCalls, inputTokens, outputTokens, cacheRead, cacheWrite,
                String.format("%.4f", costUsd));
        if (toolCalls > 0) {
            aiMessage.toolExecutionRequests().forEach(t ->
                    log.info("[agent={}]   tool={} args={}", agentName, t.name(), t.arguments()));
        }
    }

    // Anthropic pricing per million tokens (May 2026): Sonnet 4.6 / Haiku 4.5
    private static double estimateCostUsd(String agent, int input, int output, int cacheRead, int cacheWrite) {
        boolean haiku = agent.toLowerCase().contains("haiku") || agent.toLowerCase().contains("discovery");
        double inputPrice  = haiku ? 1.00  : 3.00;
        double outputPrice = haiku ? 5.00  : 15.00;
        double cacheReadP  = haiku ? 0.10  : 0.30;
        double cacheWriteP = haiku ? 1.25  : 3.75;
        return (input * inputPrice + output * outputPrice + cacheRead * cacheReadP + cacheWrite * cacheWriteP) / 1_000_000.0;
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.error("[agent={}] ✗ error: {}", agentName, errorContext.error().getMessage());
    }
}
