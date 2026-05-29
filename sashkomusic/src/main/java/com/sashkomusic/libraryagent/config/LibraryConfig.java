package com.sashkomusic.libraryagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "library")
public class LibraryConfig {

    private String rootPath;
    private Organization organization = new Organization();

    @Data
    public static class Organization {
        private boolean enabled = true;
    }
}
