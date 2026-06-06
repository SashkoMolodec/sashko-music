package com.sashkomusic.libraryagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "library")
public class LibraryConfig {

    private String rootPath;
    private List<String> sublibraries = List.of("working", "vault");
    private String defaultSublibrary = "working";
    private Organization organization = new Organization();

    @Data
    public static class Organization {
        private boolean enabled = true;
    }
}
