package com.sashkomusic.libraryagent.domain.service.tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Markers inside a track comment: the "; "-separated parts that are wholly in parentheses,
 * e.g. {@code tool; E3; (detroit techno)}. Free text such as {@code nice (live)} is not a marker.
 */
public final class MarkerComments {

    private static final String SEPARATOR = ";";

    private MarkerComments() {}

    /** Marker names in the comment, lower-cased, in order of appearance, without duplicates. */
    public static List<String> markersIn(String comment) {
        Set<String> names = new LinkedHashSet<>();
        for (String part : parts(comment)) {
            if (isMarker(part)) {
                names.add(part.substring(1, part.length() - 1).trim().toLowerCase());
            }
        }
        return new ArrayList<>(names);
    }

    /** The comment without the parts that are this marker (any case); empty when nothing else is left. */
    public static String without(String comment, String markerName) {
        String marker = "(" + markerName.trim() + ")";
        return parts(comment).stream()
                .filter(part -> !(isMarker(part) && normalized(part).equalsIgnoreCase(normalized(marker))))
                .collect(Collectors.joining(SEPARATOR + " "));
    }

    private static List<String> parts(String comment) {
        if (comment == null || comment.isBlank()) {
            return List.of();
        }
        return Arrays.stream(comment.split(SEPARATOR))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static boolean isMarker(String part) {
        return part.length() > 2 && part.startsWith("(") && part.endsWith(")") && part.indexOf(')') == part.length() - 1;
    }

    /** "( detroit techno )" and "(detroit techno)" are the same marker. */
    private static String normalized(String marker) {
        return "(" + marker.substring(1, marker.length() - 1).trim() + ")";
    }
}
