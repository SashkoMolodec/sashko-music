package com.sashkomusic.libraryagent.domain.smartlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmartlistRepository extends JpaRepository<Smartlist, Long> {

    Optional<Smartlist> findByName(String name);

    boolean existsByName(String name);
}
