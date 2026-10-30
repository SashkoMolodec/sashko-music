package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.TrackAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrackAnalysisRepository extends JpaRepository<TrackAnalysis, Long> {

    Optional<TrackAnalysis> findByTrackId(Long trackId);

    List<TrackAnalysis> findByTrackIdIn(Collection<Long> trackIds);

    boolean existsByTrackId(Long trackId);

    void deleteByTrackId(Long trackId);
}
