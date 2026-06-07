package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.libraryagent.domain.entity.Track;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
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

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Track> evaluate(SmartlistDsl dsl, int limit) {
        if (dsl == null || dsl.conditions().isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("SELECT t.* FROM tracks t WHERE ");
        Map<String, Object> params = new HashMap<>();
        int i = 0;
        boolean first = true;

        for (SmartlistDsl.Condition cond : dsl.conditions()) {
            if (!first) sql.append(" AND ");
            first = false;

            String tag = fieldMapper.tagName(cond.field());
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
                    String value = is.value();
                    if ("rating".equalsIgnoreCase(is.field())) {
                        try {
                            int stars = Integer.parseInt(value.trim());
                            if (stars >= 0 && stars <= 5) {
                                value = String.valueOf(fieldMapper.starsToWmp(stars));
                            }
                            // else: out-of-stars-range — pass through raw (likely already a WMP number)
                        } catch (NumberFormatException ignored) {
                            // pass through raw value (e.g. user passed already-wmp string)
                        }
                    }
                    params.put(valParam, value);
                    sql.append("EXISTS (SELECT 1 FROM track_tags tt WHERE tt.track_id = t.id")
                            .append(" AND tt.tag_name = :").append(nameParam)
                            .append(" AND LOWER(tt.tag_value) = LOWER(:").append(valParam).append("))");
                }
            } else if (cond instanceof SmartlistDsl.RangeCondition r) {
                if (!fieldMapper.isRangeField(r.field())) {
                    throw new IllegalArgumentException(
                            "range op is not supported on field '" + r.field() + "' (only rating, year)");
                }
                String minParam = "min" + i;
                String maxParam = "max" + i;
                int min;
                int max;
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
            i++;
        }

        sql.append(" ORDER BY t.id");

        Query q = entityManager.createNativeQuery(sql.toString(), Track.class);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            q.setParameter(e.getKey(), e.getValue());
        }
        if (limit < Integer.MAX_VALUE) {
            q.setMaxResults(limit);
        }
        return (List<Track>) q.getResultList();
    }

    public String describe(SmartlistDsl dsl) {
        if (dsl == null || dsl.conditions().isEmpty()) return "(no conditions)";
        List<String> parts = new ArrayList<>();
        for (SmartlistDsl.Condition cond : dsl.conditions()) {
            if (cond instanceof SmartlistDsl.ContainsCondition c) {
                parts.add(c.field() + " contains \"" + c.value() + "\"");
            } else if (cond instanceof SmartlistDsl.IsCondition is) {
                parts.add(is.field() + " is " + (is.value() == null ? "null" : "\"" + is.value() + "\""));
            } else if (cond instanceof SmartlistDsl.RangeCondition r) {
                String lo = r.min() == null ? "?" : String.valueOf(r.min());
                String hi = r.max() == null ? "?" : String.valueOf(r.max());
                parts.add(r.field() + " " + lo + "…" + hi);
            }
        }
        return String.join(" AND ", parts);
    }
}
