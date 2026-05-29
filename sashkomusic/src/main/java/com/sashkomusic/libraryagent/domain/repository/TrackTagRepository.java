package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackTagRepository extends JpaRepository<TrackTag, Long> {

    List<TrackTag> findByTrack(Track track);

    Optional<TrackTag> findByTrackAndTagName(Track track, String tagName);

    List<TrackTag> findByTagNameAndTagValue(String tagName, String tagValue);

    @Query("SELECT t FROM TrackTag t WHERE t.tagName = :tagName " +
           "AND CAST(t.tagValue AS integer) BETWEEN :minValue AND :maxValue")
    List<TrackTag> findByTagNameAndValueBetween(
            @Param("tagName") String tagName,
            @Param("minValue") int minValue,
            @Param("maxValue") int maxValue
    );

    void deleteByTrack(Track track);

    void deleteByTrackAndTagName(Track track, String tagName);

    long countByTrack(Track track);

    @Query("SELECT t.tagValue FROM TrackTag t WHERE t.track.id = :trackId AND t.tagName = 'RATING'")
    Optional<String> findRatingByTrackId(@Param("trackId") Long trackId);

    @Query("SELECT t.tagValue FROM TrackTag t WHERE t.track.id = :trackId AND t.tagName = :tagName")
    Optional<String> findByTrackIdAndTagName(@Param("trackId") Long trackId, @Param("tagName") String tagName);

    @Query("SELECT t FROM TrackTag t WHERE t.track.id IN :trackIds")
    List<TrackTag> findAllByTrackIds(@Param("trackIds") List<Long> trackIds);
}
