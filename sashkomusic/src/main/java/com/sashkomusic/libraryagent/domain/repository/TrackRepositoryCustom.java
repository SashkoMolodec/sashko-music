package com.sashkomusic.libraryagent.domain.repository;

import com.sashkomusic.libraryagent.domain.entity.Track;

import java.util.List;
import java.util.Map;

public interface TrackRepositoryCustom {
    List<Track> findAllByTags(Map<String, String> tagFilters);
}
