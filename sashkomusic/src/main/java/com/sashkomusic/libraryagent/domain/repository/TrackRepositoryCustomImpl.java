package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Track;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class TrackRepositoryCustomImpl implements TrackRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Track> findAllByTags(Map<String, String> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) {
            return entityManager.createQuery("SELECT DISTINCT t FROM Track t", Track.class).getResultList();
        }

        StringBuilder jpql = new StringBuilder("SELECT DISTINCT t FROM Track t ");
        int tagIndex = 0;
        for (String tagName : tagFilters.keySet()) {
            String alias = "tag" + tagIndex;
            jpql.append("LEFT JOIN TrackTag ").append(alias)
                .append(" ON ").append(alias).append(".track.id = t.id ")
                .append("AND ").append(alias).append(".tagName = :tagName").append(tagIndex).append(" ");
            tagIndex++;
        }

        jpql.append("WHERE ");
        tagIndex = 0;
        for (String tagName : tagFilters.keySet()) {
            if (tagIndex > 0) jpql.append("AND ");
            String alias = "tag" + tagIndex;
            jpql.append("LOWER(").append(alias).append(".tagValue) LIKE LOWER(:tagValue").append(tagIndex).append(") ");
            tagIndex++;
        }

        TypedQuery<Track> query = entityManager.createQuery(jpql.toString(), Track.class);
        tagIndex = 0;
        for (Map.Entry<String, String> entry : tagFilters.entrySet()) {
            query.setParameter("tagName" + tagIndex, entry.getKey());
            query.setParameter("tagValue" + tagIndex, "%" + entry.getValue() + "%");
            tagIndex++;
        }

        return query.getResultList();
    }
}
