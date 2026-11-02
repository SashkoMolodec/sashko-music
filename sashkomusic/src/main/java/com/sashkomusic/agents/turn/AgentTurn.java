package com.sashkomusic.agents.turn;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.search.SearchStack;
import dev.langchain4j.invocation.InvocationParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One free-text turn of the agent loop: the conversation it answers and everything its tools put in
 * front of the user. Handed to the tools through LangChain4j's {@link InvocationParameters}, so what a
 * tool shows travels with the turn that produced it. A turn that fails takes its cards with it —
 * there is no shared per-conversation buffer to drain or to leave stale.
 * <p>
 * Tools run one after another on the caller's thread, but {@code findSimilar} fans searches out to a
 * pool before it opens stacks, so the mutators are synchronized rather than relying on that.
 */
public final class AgentTurn {

    private static final String KEY = AgentTurn.class.getName();

    private final ConversationContext context;
    private final Consumer<String> progressSink;
    private final InvocationParameters parameters;
    private final List<BotResponse> shown = new ArrayList<>();
    private final List<SearchStack> openedStacks = new ArrayList<>();
    private final Set<String> progressSent = new HashSet<>();
    private boolean stacksReset;

    public AgentTurn(ConversationContext context, Consumer<String> progressSink) {
        this.context = Objects.requireNonNull(context, "context");
        this.progressSink = Objects.requireNonNull(progressSink, "progressSink");
        this.parameters = InvocationParameters.from(KEY, this);
    }

    /** For callers that have no "шукаю…" message to show, e.g. the {@code /library} slash command. */
    public static AgentTurn withoutProgress(ConversationContext context) {
        return new AgentTurn(context, text -> { });
    }

    /** The turn a tool runs in. A tool invoked outside {@link #parameters()} is a wiring bug, not a user error. */
    public static AgentTurn of(InvocationParameters parameters) {
        AgentTurn turn = parameters == null ? null : parameters.get(KEY);
        if (turn == null) {
            throw new IllegalStateException("tool called outside an AgentTurn — pass AgentTurn.parameters() to the agent");
        }
        return turn;
    }

    /** What the agent interface takes as its {@code InvocationParameters} argument. */
    public InvocationParameters parameters() {
        return parameters;
    }

    public ConversationContext context() {
        return context;
    }

    /** The main conversation id — never an agent's memory id. */
    public String conversationId() {
        return context.conversationId();
    }

    /** Cards, confirmations and previews go out after the agent's reply, in the order shown. */
    public synchronized void show(List<BotResponse> responses) {
        if (responses != null) shown.addAll(responses);
    }

    public synchronized List<BotResponse> shown() {
        return List.copyOf(shown);
    }

    /** "🔍 шукаю..." straight to the chat while the turn is still running; each text at most once per turn. */
    public void progress(String text) {
        synchronized (this) {
            if (!progressSent.add(text)) return;
        }
        progressSink.accept(text);
    }

    /**
     * True for the first stack this turn opens, and only then: that is when the previous answer's
     * stacks stop being "what was just found". A turn that opens none leaves them — and the paging on
     * cards already in the chat — alone.
     */
    public synchronized boolean claimStackReset() {
        if (stacksReset) return false;
        stacksReset = true;
        return true;
    }

    public synchronized void stackOpened(SearchStack stack) {
        openedStacks.add(stack);
    }

    public synchronized List<SearchStack> openedStacks() {
        return List.copyOf(openedStacks);
    }
}
