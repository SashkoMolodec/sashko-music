package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Release;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface ReleaseRepository extends JpaRepository<Release, Long> {

    Optional<Release> findBySourceId(String sourceId);

    Optional<Release> findByMasterId(String masterId);

    Optional<Release> findByDirectoryPath(String directoryPath);

    boolean existsBySourceId(String sourceId);

    /** Atomic: of two quick clicks only one gets 1 back, so the release is announced as placed once. */
    @Transactional
    @Modifying
    @Query("update Release r set r.placementPending = false where r.id = :id and r.placementPending = true")
    int clearPlacementPending(@Param("id") Long id);
}
