package com.sashkomusic.libraryagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "applemusic.sync")
public class AppleMusicSyncConfig {

    private boolean enabled = false;
    private String scriptPath;
    private String host;
    private int port;
    private int timeoutSeconds = 600;

}
