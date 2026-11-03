package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.model.OwnedRelease;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibrarySearchService {

    /**
     * The stored search_vector joined at query time with the release's track comments, where the user's
     * markers, DJ function and genres live — always fresh after a tag sync, no reindex. Phrase hits first.
     */
    private static final String SEARCH_SQL = """
            WITH q AS (
                SELECT websearch_to_tsquery('simple_unaccent', ?) AS words,
                       phraseto_tsquery('simple_unaccent', ?)     AS phrase
            ),
            comments AS (
                SELECT tr.release_id, string_agg(DISTINCT trim(part), ' ; ') AS text
                FROM track_tags tt
                JOIN tracks tr ON tr.id = tt.track_id
                CROSS JOIN LATERAL regexp_split_to_table(tt.tag_value, ';') AS part
                WHERE tt.tag_name IN ('COMM', 'COMMENT')
                GROUP BY tr.release_id
            ),
            docs AS (
                SELECT r.id,
                       coalesce(r.search_vector, ''::tsvector)
                           || setweight(to_tsvector('simple_unaccent', coalesce(c.text, '')), 'B') AS doc
                FROM releases r
                LEFT JOIN comments c ON c.release_id = r.id
            )
            SELECT
                r.id,
                r.title,
                r.initial_release,
                r.directory_path,
                ts_rank(d.doc, q.words) AS rank,
                (SELECT string_agg(a.name, ', ' ORDER BY a.name)
                 FROM release_artists ra JOIN artists a ON a.id = ra.artist_id
                 WHERE ra.release_id = r.id) AS artists,
                (SELECT string_agg(t.name, ', ' ORDER BY t.name)
                 FROM release_tags rt JOIN tags t ON t.id = rt.tag_id
                 WHERE rt.release_id = r.id) AS tags,
                (SELECT string_agg(DISTINCT lower(trim(m[1])), ', ')
                 FROM tracks tr
                 JOIN track_tags tt ON tt.track_id = tr.id
                 CROSS JOIN LATERAL regexp_matches(tt.tag_value, '\\(([^)]+)\\)', 'g') AS m
                 WHERE tr.release_id = r.id AND tt.tag_name IN ('COMM', 'COMMENT')) AS markers,
                (SELECT count(*) FROM tracks tr WHERE tr.release_id = r.id) AS track_count
            FROM docs d
            JOIN releases r ON r.id = d.id
            CROSS JOIN q
            WHERE d.doc @@ q.words
            ORDER BY (d.doc @@ q.phrase) DESC, rank DESC
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

    private static final String OWNED_SQL = """
            SELECT
                r.title,
                (SELECT string_agg(a.name, ', ' ORDER BY a.name)
                 FROM release_artists ra JOIN artists a ON a.id = ra.artist_id
                 WHERE ra.release_id = r.id) AS artists
            FROM releases r
            ORDER BY r.id DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /** The newest {@code limit} releases, so discovery can skip what the user already has. Empty on a DB error. */
    public List<OwnedRelease> ownedReleases(int limit) {
        try {
            return jdbcTemplate.query(OWNED_SQL, (rs, rowNum) -> new OwnedRelease(
                    rs.getString("artists") == null ? "" : rs.getString("artists"),
                    rs.getString("title")), limit);
        } catch (Exception e) {
            log.error("Failed to list owned releases: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<LibrarySearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = normalizeQuery(query);
        try {
            return jdbcTemplate.query(SEARCH_SQL, (rs, rowNum) -> new LibrarySearchResult(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("artists"),
                    rs.getObject("initial_release", Integer.class),
                    rs.getString("tags"),
                    rs.getString("markers"),
                    rs.getString("directory_path"),
                    rs.getInt("track_count"),
                    rs.getDouble("rank")
            ), normalized, normalized, limit);
        } catch (Exception e) {
            log.error("Library search failed for query '{}': {}", query, e.getMessage(), e);
            return List.of();
        }
    }

    private static String normalizeQuery(String query) {
        // websearch_to_tsquery treats '-' as NOT operator — replace separator hyphens with spaces
        return query.replaceAll("\\s*-\\s*", " ").trim();
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
