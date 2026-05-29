package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Release;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReleaseRepository extends JpaRepository<Release, Long> {

    Optional<Release> findBySourceId(String sourceId);

    Optional<Release> findByMasterId(String masterId);

    boolean existsBySourceId(String sourceId);
}
