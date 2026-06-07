package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.libraryagent.domain.entity.Track;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SmartlistEvaluator {

    @PersistenceContext
    private EntityManager entityManager;

    private final SmartlistFieldMapper fieldMapper;

    @Transactional(readOnly = true)
    public List<Track> evaluate(SmartlistDsl dsl) {
        return evaluate(dsl, Integer.MAX_VALUE);
    }

    /**
     * Evaluates the DSL against the library. Conditions on the <b>same field</b>
     * are joined with OR (e.g. two genre conditions → genre A OR genre B).
     * Conditions on <b>different fields</b> are joined with AND.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Track> evaluate(SmartlistDsl dsl, int limit) {
        if (dsl == null || dsl.conditions().isEmpty()) {
            return List.of();
        }

        // Preserve first-appearance order; group same-field conditions together
        Map<String, List<SmartlistDsl.Condition>> byField = new LinkedHashMap<>();
        for (SmartlistDsl.Condition cond : dsl.conditions()) {
            byField.computeIfAbsent(cond.field().toLowerCase(), k -> new ArrayList<>()).add(cond);
        }

        StringBuilder sql = new StringBuilder("SELECT t.* FROM tracks t WHERE ");
        Map<String, Object> params = new HashMap<>();
        int[] idx = {0};
        boolean firstField = true;

        for (Map.Entry<String, List<SmartlistDsl.Condition>> entry : byField.entrySet()) {
            if (!firstField) sql.append(" AND ");
            firstField = false;

            List<SmartlistDsl.Condition> conds = entry.getValue();
            boolean wrap = conds.size() > 1;
            if (wrap) sql.append("(");

            boolean firstCond = true;
            for (SmartlistDsl.Condition cond : conds) {
                if (!firstCond) sql.append(" OR ");
                firstCond = false;
                appendConditionSql(sql, params, cond, idx[0]++);
            }

            if (wrap) sql.append(")");
        }

        sql.append(" ORDER BY t.id");

        Query q = entityManager.createNativeQuery(sql.toString(), Track.class);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            q.setParameter(e.getKey(), e.getValue());
        }
        if (limit < Integer.MAX_VALUE) {
            q.setMaxResults(limit);
        }
        List<Track> result = (List<Track>) q.getResultList();
        result.forEach(t -> Hibernate.initialize(t.getArtists()));
        return result;
    }

    private void appendConditionSql(StringBuilder sql, Map<String, Object> params,
                                    SmartlistDsl.Condition cond, int i) {
        String field = cond.field().toLowerCase();

        if (fieldMapper.isDirectColumnField(field)) {
            appendDirectColumnSql(sql, params, cond, i);
            return;
        }

        String tag = fieldMapper.tagName(field);
        String nameParam = "n" + i;
        params.put(nameParam, tag);

        if (cond instanceof SmartlistDsl.ContainsCondition c) {
            String valParam = "v" + i;
            params.put(valParam, "%" + (c.value() == null ? "" : c.value()) + "%");
            sql.append("EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                    .append(" AND tt.tag_name = :").append(nameParam)
                    .append(" AND tt.tag_value ILIKE :").append(valParam).append(")");

        } else if (cond instanceof SmartlistDsl.IsCondition is) {
            if (is.value() == null) {
                sql.append("NOT EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                        .append(" AND tt.tag_name = :").append(nameParam)
                        .append(" AND tt.tag_value IS NOT NULL AND tt.tag_value <> '')");
            } else {
                String valParam = "v" + i;
                params.put(valParam, resolveIsValue(is));
                sql.append("EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                        .append(" AND tt.tag_name = :").append(nameParam)
                        .append(" AND LOWER(tt.tag_value) = LOWER(:").append(valParam).append("))");
            }

        } else if (cond instanceof SmartlistDsl.NumericComparison cmp) {
            if (!fieldMapper.isRangeField(cmp.field())) {
                throw new IllegalArgumentException(
                        cmp.sqlOperator() + " op is not supported on field '" + cmp.field() + "' (only rating, year)");
            }
            if (cmp.value() == null) {
                throw new IllegalArgumentException(cmp.sqlOperator() + " op requires a value on field '" + cmp.field() + "'");
            }
            int v = fieldMapper.usesStarsScale(cmp.field())
                    ? fieldMapper.starsToWmp(cmp.value())
                    : cmp.value();
            String valParam = "v" + i;
            params.put(valParam, v);
            sql.append("EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                    .append(" AND tt.tag_name = :").append(nameParam)
                    .append(" AND tt.tag_value ~ '^[0-9]+$'")
                    .append(" AND tt.tag_value::integer ").append(cmp.sqlOperator()).append(" :")
                    .append(valParam).append(")");

        } else if (cond instanceof SmartlistDsl.RangeCondition r) {
            if (!fieldMapper.isRangeField(r.field())) {
                throw new IllegalArgumentException(
                        "range op is not supported on field '" + r.field() + "' (only rating, year)");
            }
            String minParam = "min" + i;
            String maxParam = "max" + i;
            int min, max;
            if (fieldMapper.usesStarsScale(r.field())) {
                min = r.min() == null ? 0 : fieldMapper.starsToWmp(r.min());
                max = r.max() == null ? 255 : fieldMapper.starsToWmp(r.max());
            } else {
                min = r.min() == null ? Integer.MIN_VALUE : r.min();
                max = r.max() == null ? Integer.MAX_VALUE : r.max();
            }
            params.put(minParam, min);
            params.put(maxParam, max);
            sql.append("EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                    .append(" AND tt.tag_name = :").append(nameParam)
                    .append(" AND tt.tag_value ~ '^[0-9]+$'")
                    .append(" AND tt.tag_value::integer BETWEEN :")
                    .append(minParam).append(" AND :").append(maxParam).append(")");
        }
    }

    /** Handles conditions on direct {@code tracks} columns (e.g. {@code sublibrary}). */
    private void appendDirectColumnSql(StringBuilder sql, Map<String, Object> params,
                                       SmartlistDsl.Condition cond, int i) {
        String col = fieldMapper.columnName(cond.field());

        if (cond instanceof SmartlistDsl.ContainsCondition c) {
            String valParam = "v" + i;
            params.put(valParam, "%" + (c.value() == null ? "" : c.value()) + "%");
            sql.append("t.").append(col).append(" ILIKE :").append(valParam);

        } else if (cond instanceof SmartlistDsl.IsCondition is) {
            if (is.value() == null) {
                sql.append("t.").append(col).append(" IS NULL");
            } else {
                String valParam = "v" + i;
                params.put(valParam, is.value());
                sql.append("LOWER(t.").append(col).append(") = LOWER(:").append(valParam).append(")");
            }

        } else {
            throw new IllegalArgumentException(
                    "only 'contains' and 'is' ops are supported on field '" + cond.field() + "'");
        }
    }

    private String resolveIsValue(SmartlistDsl.IsCondition is) {
        if ("rating".equalsIgnoreCase(is.field()) && is.value() != null) {
            try {
                int stars = Integer.parseInt(is.value().trim());
                if (stars >= 0 && stars <= 5) {
                    return String.valueOf(fieldMapper.starsToWmp(stars));
                }
            } catch (NumberFormatException ignored) {
                // pass through raw value
            }
        }
        return is.value();
    }

    public String describe(SmartlistDsl dsl) {
        if (dsl == null || dsl.conditions().isEmpty()) return "(no conditions)";

        Map<String, List<SmartlistDsl.Condition>> byField = new LinkedHashMap<>();
        for (SmartlistDsl.Condition cond : dsl.conditions()) {
            byField.computeIfAbsent(cond.field().toLowerCase(), k -> new ArrayList<>()).add(cond);
        }

        List<String> andParts = new ArrayList<>();
        for (Map.Entry<String, List<SmartlistDsl.Condition>> entry : byField.entrySet()) {
            List<String> orParts = entry.getValue().stream().map(this::describeOne).toList();
            andParts.add(orParts.size() > 1 ? "(" + String.join(" OR ", orParts) + ")" : orParts.get(0));
        }
        return String.join(" AND ", andParts);
    }

    private String describeOne(SmartlistDsl.Condition cond) {
        if (cond instanceof SmartlistDsl.ContainsCondition c) {
            return c.field() + " contains \"" + c.value() + "\"";
        } else if (cond instanceof SmartlistDsl.IsCondition is) {
            return is.field() + " is " + (is.value() == null ? "null" : "\"" + is.value() + "\"");
        } else if (cond instanceof SmartlistDsl.NumericComparison cmp) {
            return cmp.field() + " " + cmp.sqlOperator() + " " + cmp.value();
        } else if (cond instanceof SmartlistDsl.RangeCondition r) {
            String lo = r.min() == null ? "?" : String.valueOf(r.min());
            String hi = r.max() == null ? "?" : String.valueOf(r.max());
            return r.field() + " " + lo + "…" + hi;
        }
        return cond.field();
    }
}
