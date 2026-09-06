package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Marker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarkerRepository extends JpaRepository<Marker, Long> {

    Optional<Marker> findByName(String name);

    boolean existsByName(String name);

    @Query("SELECT m FROM Marker m ORDER BY m.lastUsedAt DESC NULLS LAST, m.name ASC")
    List<Marker> findAllByOrderByLastUsedAtDescNameAsc();
}
