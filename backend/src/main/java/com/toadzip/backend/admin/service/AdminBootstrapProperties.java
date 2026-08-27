package com.toadzip.backend.admin.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admin.bootstrap")
public record AdminBootstrapProperties(boolean enabled, String loginIdentifier, String password) {
}
