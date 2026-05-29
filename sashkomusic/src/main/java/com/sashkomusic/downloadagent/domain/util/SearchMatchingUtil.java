package com.sashkomusic.downloadagent.domain.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SearchMatchingUtil {

    /**
     * Checks if all words from both search artist and search title are present
     * in the corresponding result fields (case-insensitive, whole-word matching).
     */
    public static boolean matches(String searchArtist, String searchTitle,
                                  String resultArtist, String resultTitle) {
        boolean artistMatches = matchesAllWords(searchArtist, resultArtist);
        boolean titleMatches = matchesAllWords(searchTitle, resultTitle);
        return artistMatches && titleMatches;
    }

    private static boolean matchesAllWords(String search, String result) {
        if (search == null || search.isBlank() || result == null || result.isBlank()) {
            return false;
        }

        String searchLower = search.toLowerCase(Locale.ROOT);
        String resultLower = result.toLowerCase(Locale.ROOT);

        // Split by non-word characters
        String[] searchWords = searchLower.split("[\\s\\p{Punct}]+");
        String[] resultWords = resultLower.split("[\\s\\p{Punct}]+");

        List<String> resultWordList = Arrays.stream(resultWords)
                .filter(word -> !word.isEmpty())
                .toList();

        // Check if all search words exist as whole words in result
        return Arrays.stream(searchWords)
                .filter(word -> !word.isEmpty())
                .allMatch(searchWord -> resultWordList.contains(searchWord));
    }
}