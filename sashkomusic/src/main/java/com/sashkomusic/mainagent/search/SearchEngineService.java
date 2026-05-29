package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.TrackMetadata;

import java.util.List;

public interface SearchEngineService {

    List<ReleaseMetadata> searchReleases(MetadataSearchRequest request);

    List<TrackMetadata> getTracks(String releaseId);

    String getName();

    SearchEngine getSource();

    String buildReleaseUrl(ReleaseMetadata release);

    ReleaseMetadata getReleaseMetadata(ReleaseMetadataFile metadataFile);
}
