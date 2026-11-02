package com.sashkomusic.agents.discovery;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Answers one factual music question from the web — the {@code researchMusic} tool's backend. Runs on
 * {@code webSearchChatModel}, the one model bean carrying Anthropic's server-side web search, with no
 * memory: MainAgent resolves the conversation into a self-contained question before calling it, and
 * keeping the server-tool blocks here keeps them out of Sonnet's thinking-carrying tool loop.
 */
public interface MusicResearcher {

    @SystemMessage("""
            You answer one music question for a Telegram music bot. Use web search for every answer —
            do not answer from memory alone, and state only what the sources say.
            Reply in Ukrainian, 3-5 sentences, lowercase, plain text, no markdown, no lists.
            If one source is clearly the best, append its bare URL on its own line at the end.
            If the search finds nothing reliable, say so in one sentence instead of guessing.
            """)
    @UserMessage("{{it}}")
    String research(String question);
}
