package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LabelRepository extends JpaRepository<Label, Long> {

    Optional<Label> findByName(String name);
}
