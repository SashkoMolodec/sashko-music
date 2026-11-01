package com.sashkomusic.mainagent.process;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * What follows {@code /process}: a folder, and optionally a hint for recognising the release
 * ({@code /process /Volumes/x/arnika Арніка – Естрадний Ансамбль Арніка}).
 */
record ProcessInput(String folder, String context) {

    /**
     * An absolute path is cut after its longest run of words that names an existing folder, so a folder
     * with spaces in its name still wins over the hint behind it. Anything else is left whole.
     */
    static ProcessInput split(String rest, Predicate<String> namesFolder) {
        if (rest == null || !rest.startsWith("/")) return new ProcessInput(rest, "");
        String[] words = rest.trim().split("\\s+");
        for (int count = words.length; count >= 1; count--) {
            String candidate = String.join(" ", Arrays.copyOfRange(words, 0, count));
            if (namesFolder.test(candidate)) {
                String context = String.join(" ", Arrays.copyOfRange(words, count, words.length));
                return new ProcessInput(candidate, context);
            }
        }
        return new ProcessInput(rest, "");
    }
}
