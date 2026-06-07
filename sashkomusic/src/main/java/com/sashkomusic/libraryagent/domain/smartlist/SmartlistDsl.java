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
            @JsonSubTypes.Type(value = IsCondition.class, name = "is"),
            @JsonSubTypes.Type(value = GtCondition.class, name = "gt"),
            @JsonSubTypes.Type(value = GteCondition.class, name = "gte"),
            @JsonSubTypes.Type(value = LtCondition.class, name = "lt"),
            @JsonSubTypes.Type(value = LteCondition.class, name = "lte")
    })
    public sealed interface Condition
            permits ContainsCondition, RangeCondition, IsCondition, NumericComparison {
        String field();
    }

    public record ContainsCondition(String field, String value) implements Condition {}

    public record RangeCondition(String field, Integer min, Integer max) implements Condition {}

    /**
     * Exact-match condition. {@code value == null} means the tag must be absent
     * (or empty) on the track — i.e. "rating is null", "comment is null".
     */
    public record IsCondition(String field, String value) implements Condition {}

    /** Numeric comparison conditions (year, rating). Value uses field's natural scale. */
    public sealed interface NumericComparison extends Condition
            permits GtCondition, GteCondition, LtCondition, LteCondition {
        Integer value();
        String sqlOperator();
    }

    public record GtCondition(String field, Integer value) implements NumericComparison {
        @Override public String sqlOperator() { return ">"; }
    }

    public record GteCondition(String field, Integer value) implements NumericComparison {
        @Override public String sqlOperator() { return ">="; }
    }

    public record LtCondition(String field, Integer value) implements NumericComparison {
        @Override public String sqlOperator() { return "<"; }
    }

    public record LteCondition(String field, Integer value) implements NumericComparison {
        @Override public String sqlOperator() { return "<="; }
    }
}
