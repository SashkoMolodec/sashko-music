package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class AgentTraceListener implements ChatModelListener {

    // Mirrors AnthropicMapper.SERVER_TOOL_RESULTS_KEY — copied rather than imported, that class
    // sits in langchain4j's `internal` package.
    private static final String SERVER_TOOL_RESULTS_KEY = "server_tool_results";

    public record CallEntry(String agent, List<String> toolNames, int tokensIn, int tokensOut,
                             int cacheRead, int cacheWrite, double cost) {}

    private static final ConcurrentHashMap<String, List<CallEntry>> flowCalls = new ConcurrentHashMap<>();

    public static double drainFlowCost(String flowId) {
        if (flowId == null) return 0.0;
        List<CallEntry> calls = flowCalls.get(flowId);
        if (calls == null) return 0.0;
        return calls.stream().mapToDouble(CallEntry::cost).sum();
    }

    public static List<CallEntry> drainFlowCalls(String flowId) {
        if (flowId == null) return List.of();
        List<CallEntry> calls = flowCalls.remove(flowId);
        return calls == null ? List.of() : calls;
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

        if (req.messages() != null) {
            req.messages().stream()
                    .filter(m -> m instanceof ToolExecutionResultMessage)
                    .map(m -> (ToolExecutionResultMessage) m)
                    .forEach(r -> log.info("[agent={}]   ↩ tool-result name={} result={}",
                            agentName, r.toolName(), truncate(r.text(), 300)));
        }
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
        List<String> toolNames = toolCalls > 0
                ? aiMessage.toolExecutionRequests().stream().map(t -> t.name()).collect(Collectors.toList())
                : List.of();
        if (flowId != null) {
            CallEntry entry = new CallEntry(agentName, toolNames, inputTokens, outputTokens,
                    cacheRead, cacheWrite, costUsd);
            flowCalls.computeIfAbsent(flowId, k -> new ArrayList<>()).add(entry);
        }
        log.info("[agent={}] [flow={}] ← response toolCalls={} tokensIn={} tokensOut={} cacheRead={} cacheWrite={} cost=${}",
                agentName, flowId, toolCalls, inputTokens, outputTokens, cacheRead, cacheWrite,
                String.format("%.4f", costUsd));
        if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
            log.info("[agent={}]   💬 text={}", agentName, truncate(aiMessage.text(), 300));
        }
        if (toolCalls > 0) {
            aiMessage.toolExecutionRequests().forEach(t ->
                    log.info("[agent={}]   tool={} args={}", agentName, t.name(), t.arguments()));
        }
        // toolCalls counts client-side @Tool calls only — Anthropic's server-side web_search runs inside
        // the same API response, so without this it is invisible whether the model searched or answered
        // from its own knowledge.
        Object serverToolResults = aiMessage.attributes().get(SERVER_TOOL_RESULTS_KEY);
        if (serverToolResults != null) {
            log.info("[agent={}]   🌐 server-tool-results={}", agentName, truncate(String.valueOf(serverToolResults), 500));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
