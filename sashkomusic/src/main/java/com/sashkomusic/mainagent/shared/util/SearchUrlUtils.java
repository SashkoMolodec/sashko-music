package com.sashkomusic.mainagent.shared.util;

import com.sashkomusic.mainagent.shared.model.Language;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SearchUrlUtils {

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static Language detectLanguage(String artist, String title) {
        String combined = artist + " " + title;
        if (combined.matches(".*[іїєґ].*")) {
            return Language.UA;
        }
        return Language.EN;
    }

    public static String buildYoutubeAlbumWord(Language language) {
        return switch (language) {
            case UA -> "альбом";
            case EN -> "album";
        };
    }

    public static String buildSearchQuery(String artist, String title) {
        return artist + " " + title;
    }

    public static String buildDiscogsSearchUrl(String artist, String title) {
        String query = buildSearchQuery(artist, title);
        return "URL:https://www.discogs.com/search/?q=" + encode(query);
    }
}
