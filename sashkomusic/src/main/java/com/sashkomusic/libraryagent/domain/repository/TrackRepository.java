package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackRepository extends JpaRepository<Track, Long>, TrackRepositoryCustom {

    Optional<Track> findByLocalPath(String localPath);

    Optional<Track> findByTitle(String title);

    @Query("""
        SELECT DISTINCT t FROM Track t
        JOIN FETCH t.artists a
        WHERE REPLACE(REPLACE(LOWER(t.title), ' ', ''), '+', '') = REPLACE(REPLACE(LOWER(:title), ' ', ''), '+', '')
        AND REPLACE(REPLACE(LOWER(a.name), ' ', ''), '+', '') = REPLACE(REPLACE(LOWER(:artist), ' ', ''), '+', '')
        """)
    List<Track> findByArtistAndTitle(@Param("artist") String artist, @Param("title") String title);
}
