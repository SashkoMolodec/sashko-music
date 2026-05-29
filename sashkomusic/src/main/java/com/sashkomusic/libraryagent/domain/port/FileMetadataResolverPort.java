package com.sashkomusic.libraryagent.domain.port;

import com.sashkomusic.libraryagent.domain.model.ResolvedRelease;

import java.util.List;

public interface FileMetadataResolverPort {

    ResolvedRelease resolveFiles(String releaseId, List<String> downloadedFiles);
}
