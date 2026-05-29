package com.sashkomusic.mainagent.process;

import com.sashkomusic.mainagent.process.config.PathMappingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final PathMappingConfig pathMappingConfig;

    public String mapProcessPath(String originalPath) {
        return mapPath(originalPath, pathMappingConfig.getProcessSource(), pathMappingConfig.getProcessTarget());
    }

    public String mapReprocessPath(String originalPath) {
        return mapPath(originalPath, pathMappingConfig.getReprocessSource(), pathMappingConfig.getReprocessTarget());
    }

    private String mapPath(String originalPath, String source, String target) {
        if (!pathMappingConfig.isEnabled() || originalPath == null) {
            return originalPath;
        }

        if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
            return originalPath;
        }

        if (originalPath.startsWith(source)) {
            // Use replaceFirst to only replace the first occurrence (the base path)
            return originalPath.replaceFirst(java.util.regex.Pattern.quote(source), target);
        }

        return originalPath;
    }
}
