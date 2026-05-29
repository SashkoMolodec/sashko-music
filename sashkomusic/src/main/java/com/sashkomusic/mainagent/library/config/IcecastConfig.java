package com.sashkomusic.mainagent.library.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "icecast")
public class IcecastConfig {

    private String baseUrl = "http://icecast:8000";

    private String mountpoint = "/stream";

    private int timeoutMs = 5000;

    private boolean enabled = true;
}