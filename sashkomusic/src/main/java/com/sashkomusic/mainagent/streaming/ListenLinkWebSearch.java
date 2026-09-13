package com.sashkomusic.mainagent.streaming;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Last-resort listen-link source, used only when every structured lookup in
 * {@link ListenLinkResolver} came up empty. Backed by {@code discoveryChatModel}, the one model
 * bean carrying Anthropic's server-side web_search tool.
 */
public interface ListenLinkWebSearch {

    @SystemMessage("""
            You find one working public URL where a specific music release can be listened to online.
            Always use web search — never answer from your own memory.
            Prefer, in this order: YouTube, YouTube Music, Bandcamp, SoundCloud.
            A link to a single track from the release is acceptable if the full release is not streamable.
            Do NOT return search-result pages, store/purchase-only pages, Discogs, Wikipedia, or review sites.
            Reply with the bare URL and nothing else — no markdown, no quotes, no explanation.
            If you cannot find one, reply exactly: NONE
            """)
    @UserMessage("{{artist}} — {{title}}")
    String findListenUrl(@V("artist") String artist, @V("title") String title);
}
