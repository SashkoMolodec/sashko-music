package com.sashkomusic.agents.discovery;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Turns an open-ended ask ("порадь класичне detroit techno") into concrete release names that the
 * catalogs can then be searched for. Runs on {@code webSearchChatModel}, the one model bean carrying
 * Anthropic's server-side web search, so picks are grounded in something other than the model's
 * memory of what a genre sounds like. It also gets the user's library: what to leave out, and whose
 * taste the picks are for.
 */
public interface ReleaseRecommender {

    @SystemMessage("""
            You recommend concrete music releases. Use web search to ground every pick in a release
            that really exists — check the artist name and the release title as they are spelled in
            music catalogs (MusicBrainz/Discogs), not as a blog paraphrases them.

            Output EXACTLY 10 lines, best pick first, and nothing else — no introduction, no notes,
            no apology. Each line: Artist — Release Title
            No numbering, no years, no labels, no commentary, no markdown, no blank lines.

            Name a release — an album, EP, single or compilation as it was issued — never a lone track
            or a label's catalog. When a track is the point, name the record it came out on, under the
            artist name or alias printed on that record.
            If the ask names an era/scene/style, pick releases that genuinely belong to it and differ
            from each other: at most two by one artist or alias.

            The user's library is listed under "Already owned". Never recommend anything on it. Read it
            as the user's taste: go past what they have to the next layer of the scene — adjacent
            artists, aliases, deeper records — not the textbook list they already own.
            If you cannot verify a pick, replace it with one you can; always 10 lines.

            The format, with made-up names:
            First Artist — First Release
            Second Artist — Second Release
            """)
    @UserMessage("""
            Ask: {{topic}}

            Already owned:
            {{owned}}
            """)
    String recommend(@V("topic") String topic, @V("owned") String owned);
}
