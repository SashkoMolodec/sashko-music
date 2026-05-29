package com.sashkomusic.downloadagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Configuration
@ConfigurationProperties(prefix = "slskd.downloads")
@Getter
public class SlskdPathConfig {
    private String containerPath;
    private String localPath;

    public String transformToLocalPath(String containerFilePath) {
        if (containerFilePath == null || containerPath == null || localPath == null) {
            return containerFilePath;
        }

        if (containerFilePath.startsWith(containerPath)) {
            return containerFilePath.replace(containerPath, localPath);
        }

        return containerFilePath;
    }
}
