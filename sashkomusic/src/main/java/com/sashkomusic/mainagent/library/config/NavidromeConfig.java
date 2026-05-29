package com.sashkomusic.mainagent.library.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "navidrome")
public class NavidromeConfig {

    private String baseUrl = "http://navidrome:4533";
    private String username = "admin";
    private String password;
    private String apiVersion = "1.16.1";
    private String clientName = "sm-main-agent";
    private String libraryId = "1";
    private String libraryPath = "/music";

}