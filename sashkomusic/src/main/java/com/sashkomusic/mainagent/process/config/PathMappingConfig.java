package com.sashkomusic.mainagent.process.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "path.mapping")
public class PathMappingConfig {
    private boolean enabled;
    private String reprocessSource;
    private String reprocessTarget;
    private String processSource;
    private String processTarget;
}
