package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibrarySearchService {

    private static final String SEARCH_SQL = """
            SELECT
                r.id,
                r.title,
                r.initial_release,
                r.directory_path,
                ts_rank(r.search_vector, websearch_to_tsquery('simple_unaccent', ?)) AS rank,
                (SELECT string_agg(a.name, ', ' ORDER BY a.name)
                 FROM release_artists ra JOIN artists a ON a.id = ra.artist_id
                 WHERE ra.release_id = r.id) AS artists,
                (SELECT string_agg(t.name, ', ' ORDER BY t.name)
                 FROM release_tags rt JOIN tags t ON t.id = rt.tag_id
                 WHERE rt.release_id = r.id) AS tags,
                (SELECT count(*) FROM tracks tr WHERE tr.release_id = r.id) AS track_count
            FROM releases r
            WHERE r.search_vector @@ websearch_to_tsquery('simple_unaccent', ?)
            ORDER BY rank DESC
            LIMIT ?
            """;

    private static final String INDEX_ONE_SQL = """
            WITH agg AS (
                SELECT
                    r.id,
                    r.title,
                    string_agg(DISTINCT a.name, ' ')  AS artists,
                    string_agg(DISTINCT t.name, ' ')  AS genre_tags,
                    string_agg(DISTINCT tr.title, ' ') AS track_titles
                FROM releases r
                LEFT JOIN release_artists ra  ON ra.release_id  = r.id
                LEFT JOIN artists a           ON a.id           = ra.artist_id
                LEFT JOIN release_tags rta    ON rta.release_id = r.id
                LEFT JOIN tags t              ON t.id           = rta.tag_id
                LEFT JOIN tracks tr           ON tr.release_id  = r.id
                WHERE r.id = ?
                GROUP BY r.id, r.title
            )
            UPDATE releases
            SET search_vector = (
                setweight(to_tsvector('simple_unaccent', coalesce(agg.title, '')),       'A') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.artists, '')),     'A') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.genre_tags, '')),  'B') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.track_titles, '')), 'C')
            )
            FROM agg
            WHERE releases.id = agg.id
            """;

    private static final String REINDEX_ALL_SQL = """
            WITH agg AS (
                SELECT
                    r.id,
                    r.title,
                    string_agg(DISTINCT a.name, ' ')  AS artists,
                    string_agg(DISTINCT t.name, ' ')  AS genre_tags,
                    string_agg(DISTINCT tr.title, ' ') AS track_titles
                FROM releases r
                LEFT JOIN release_artists ra  ON ra.release_id  = r.id
                LEFT JOIN artists a           ON a.id           = ra.artist_id
                LEFT JOIN release_tags rta    ON rta.release_id = r.id
                LEFT JOIN tags t              ON t.id           = rta.tag_id
                LEFT JOIN tracks tr           ON tr.release_id  = r.id
                GROUP BY r.id, r.title
            )
            UPDATE releases
            SET search_vector = (
                setweight(to_tsvector('simple_unaccent', coalesce(agg.title, '')),       'A') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.artists, '')),     'A') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.genre_tags, '')),  'B') ||
                setweight(to_tsvector('simple_unaccent', coalesce(agg.track_titles, '')), 'C')
            )
            FROM agg
            WHERE releases.id = agg.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<LibrarySearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(SEARCH_SQL, (rs, rowNum) -> new LibrarySearchResult(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("artists"),
                    rs.getObject("initial_release", Integer.class),
                    rs.getString("tags"),
                    rs.getString("directory_path"),
                    rs.getInt("track_count"),
                    rs.getDouble("rank")
            ), query, query, limit);
        } catch (Exception e) {
            log.error("Library search failed for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }

    public void indexRelease(Long releaseId) {
        try {
            jdbcTemplate.update(INDEX_ONE_SQL, releaseId);
            log.debug("Indexed search vector for release id={}", releaseId);
        } catch (Exception e) {
            log.error("Failed to index search vector for release id={}: {}", releaseId, e.getMessage(), e);
        }
    }

    public int reindexAll() {
        try {
            int updated = jdbcTemplate.update(REINDEX_ALL_SQL);
            log.info("Reindexed search vectors for {} releases", updated);
            return updated;
        } catch (Exception e) {
            log.error("Failed to reindex all releases: {}", e.getMessage(), e);
            return 0;
        }
    }
}
