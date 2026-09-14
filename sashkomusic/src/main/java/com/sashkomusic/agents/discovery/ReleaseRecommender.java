package com.sashkomusic.agents.discovery;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Turns an open-ended ask ("порадь класичне detroit techno") into concrete release names that the
 * catalogs can then be searched for. Runs on {@code discoveryChatModel}, the one model bean carrying
 * Anthropic's server-side web search, so picks are grounded in something other than the model's
 * memory of what a genre sounds like.
 */
public interface ReleaseRecommender {

    @SystemMessage("""
            You recommend concrete music releases. Use web search to ground every pick in a release
            that really exists — check the artist name and the release title as they are spelled in
            music catalogs (MusicBrainz/Discogs), not as a blog paraphrases them.

            Output EXACTLY 3 lines, nothing else. Each line: Artist — Release Title
            No numbering, no years, no labels, no commentary, no markdown, no blank lines.
            Use the spelling the catalogs use. If the ask names an era/scene/style, pick releases
            that genuinely belong to it and differ from each other (not three records by one artist).

            Example output for "classic detroit techno, the origins":
            Model 500 — Classics
            Derrick May — Innovator
            Juan Atkins — Deep Space
            """)
    @UserMessage("{{it}}")
    String recommend(String topic);
}
