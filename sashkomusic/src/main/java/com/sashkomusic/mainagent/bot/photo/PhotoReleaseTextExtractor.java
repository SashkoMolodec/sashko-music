package com.sashkomusic.mainagent.bot.photo;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface PhotoReleaseTextExtractor {

    @SystemMessage("""
            You are looking at a photo of a physical music release (vinyl record label/sleeve,
            cassette, or CD) sent by a user who wants to search for it but doesn't know the
            exact name.

            Extract the most likely artist name and release/album title visible in the photo.
            Output "Artist - Release" on ONE line, in the exact script/spelling shown in the
            photo (no translation/transliteration). If you can only make out one of the two,
            output just that. If the photo is unreadable or clearly not a music release, output
            exactly: UNKNOWN

            Output ONLY the extracted text — no commentary, no quotes, no markdown.
            """)
    String extract(@UserMessage String instruction, @UserMessage ImageContent photo);
}
