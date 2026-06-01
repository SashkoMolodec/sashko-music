package com.sashkomusic.agents.library;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.agents.contract.LibraryRequest;
import com.sashkomusic.agents.contract.LibraryResult;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.mainagent.library.DjTagFlowService;
import com.sashkomusic.mainagent.library.NowPlayingFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryAgentService {

    private final DjTagFlowService djTagFlowService;
    private final NowPlayingFlowService nowPlayingFlowService;
    private final DjTagContextHolder djTagContextHolder;
    private final LibraryCommandParser parser;
    private final ChatResponseAccumulator accumulator;

    public LibraryResult handle(LibraryRequest request) {
        log.info("Library agent handling: conversationId={}, command='{}'", request.conversationId(), request.naturalCommand());
        try {
            return route(request);
        } catch (Exception ex) {
            log.error("Library agent failure for conversation {}: {}", request.conversationId(), ex.getMessage(), ex);
            return LibraryResult.failed("шось накрилось: " + ex.getMessage());
        }
    }

    private LibraryResult route(LibraryRequest request) {
        LibraryCommand cmd = parser.parse(request.naturalCommand());
        var context = djTagContextHolder.getContext(request.conversationId());

        if (!(cmd instanceof LibraryCommand.Unknown) && context == null) {
            return LibraryResult.failed("нема активного треку — спочатку /np");
        }

        ConversationContext ctx = ConversationContext.from(request.conversationId());

        return switch (cmd) {
            case LibraryCommand.Rate r -> {
                pushAll(request.conversationId(), nowPlayingFlowService.rateTrack(ctx, context.trackId(), r.stars()));
                yield LibraryResult.ok("оцінив на %d".formatted(r.stars()));
            }
            case LibraryCommand.SetEnergy e -> {
                pushAll(request.conversationId(), djTagFlowService.setDjEnergy(ctx, context.trackId(), e.level()));
                yield LibraryResult.ok("energy=" + e.level());
            }
            case LibraryCommand.SetFunction f -> {
                pushAll(request.conversationId(), djTagFlowService.setDjFunction(ctx, context.trackId(), f.function()));
                yield LibraryResult.ok("function=" + f.function());
            }
            case LibraryCommand.AddComment c -> {
                pushAll(request.conversationId(), djTagFlowService.addComment(ctx, context.trackId(), c.text()));
                yield LibraryResult.ok("коментар додано");
            }
            case LibraryCommand.Unknown u -> LibraryResult.failed(u.reason());
        };
    }

    private void pushAll(String conversationId, List<BotResponse> responses) {
        accumulator.pushAll(conversationId, responses);
    }
}
