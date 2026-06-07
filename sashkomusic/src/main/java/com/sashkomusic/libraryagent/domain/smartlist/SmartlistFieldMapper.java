package com.sashkomusic.libraryagent.domain.smartlist;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SmartlistFieldMapper {

    public static final Set<String> CONTAINS_FIELDS = Set.of("year", "comment", "label", "genre");
    public static final Set<String> RANGE_FIELDS = Set.of("rating", "year");
    public static final Set<String> IS_FIELDS = Set.of("year", "comment", "label", "genre", "rating");
    /** Fields backed by a direct column on {@code tracks}, not by {@code track_tags} rows. */
    public static final Set<String> DIRECT_COLUMN_FIELDS = Set.of("sublibrary");
    public static final Set<String> ALL_FIELDS = Set.of("year", "comment", "label", "genre", "rating", "sublibrary");

    public String tagName(String field) {
        return switch (field.toLowerCase()) {
            case "year" -> "TDRC";
            case "comment" -> "COMM";
            case "label" -> "PUBLISHER";
            case "genre" -> "TCON";
            case "rating" -> "RATING";
            default -> throw new IllegalArgumentException("unknown smartlist tag field: " + field);
        };
    }

    /** Column name on {@code tracks} table for direct-column fields. */
    public String columnName(String field) {
        return switch (field.toLowerCase()) {
            case "sublibrary" -> "sublibrary";
            default -> throw new IllegalArgumentException("not a direct column field: " + field);
        };
    }

    public boolean isDirectColumnField(String field) {
        return DIRECT_COLUMN_FIELDS.contains(field.toLowerCase());
    }

    public boolean isRangeField(String field) {
        return RANGE_FIELDS.contains(field.toLowerCase());
    }

    public boolean isContainsField(String field) {
        return CONTAINS_FIELDS.contains(field.toLowerCase());
    }

    public boolean isIsField(String field) {
        return IS_FIELDS.contains(field.toLowerCase());
    }

    /** Only the rating field uses the 1..5 stars → WMP 0..255 mapping. */
    public boolean usesStarsScale(String field) {
        return "rating".equalsIgnoreCase(field);
    }

    /**
     * Rating is stored as Windows Media Player numeric (0..255) representing 1..5 stars.
     * 1★ = 51, 2★ = 102, 3★ = 153, 4★ = 204, 5★ = 255.
     * Convert a user-facing stars value into the WMP scale used in the DB.
     */
    public int starsToWmp(int stars) {
        return switch (stars) {
            case 0 -> 0;
            case 1 -> 51;
            case 2 -> 102;
            case 3 -> 153;
            case 4 -> 204;
            case 5 -> 255;
            default -> throw new IllegalArgumentException("rating stars out of range 0..5: " + stars);
        };
    }
}
