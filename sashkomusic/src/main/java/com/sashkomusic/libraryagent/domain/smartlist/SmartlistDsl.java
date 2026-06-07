package com.sashkomusic.libraryagent.domain.smartlist;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

public record SmartlistDsl(List<Condition> conditions) {

    public SmartlistDsl {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContainsCondition.class, name = "contains"),
            @JsonSubTypes.Type(value = RangeCondition.class, name = "range"),
            @JsonSubTypes.Type(value = IsCondition.class, name = "is")
    })
    public sealed interface Condition permits ContainsCondition, RangeCondition, IsCondition {
        String field();
    }

    public record ContainsCondition(String field, String value) implements Condition {}

    public record RangeCondition(String field, Integer min, Integer max) implements Condition {}

    /**
     * Exact-match condition. {@code value == null} means the tag must be absent
     * (or empty) on the track — i.e. "rating is null", "comment is null".
     */
    public record IsCondition(String field, String value) implements Condition {}
}
