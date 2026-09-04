package com.toadzip.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class LoggingProfileConfigurationTest {

    private static final String APPLICATION_LOG_LEVEL = "logging.level.com.toadzip.backend";
    private static final String ROOT_LOG_LEVEL = "logging.level.root";

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void 환경별_애플리케이션_로그_레벨을_설정한다() throws IOException {
        assertThat(logLevelOf("local")).isEqualTo("DEBUG");
        assertThat(logLevelOf("dev")).isEqualTo("INFO");
        assertThat(logLevelOf("prod")).isEqualTo("INFO");
    }

    @Test
    void 모든_환경에서_기본_로그_레벨을_INFO로_제한한다() throws IOException {
        assertThat(rootLogLevelOf("local")).isEqualTo("INFO");
        assertThat(rootLogLevelOf("dev")).isEqualTo("INFO");
        assertThat(rootLogLevelOf("prod")).isEqualTo("INFO");
    }

    private Object logLevelOf(String profile) throws IOException {
        return propertyOf(profile, APPLICATION_LOG_LEVEL);
    }

    private Object rootLogLevelOf(String profile) throws IOException {
        return propertyOf(profile, ROOT_LOG_LEVEL);
    }

    private Object propertyOf(String profile, String propertyName) throws IOException {
        ClassPathResource resource = new ClassPathResource("application-" + profile + ".yml");
        return loader.load(profile, resource)
                .stream()
                .map(propertySource -> propertySource.getProperty(propertyName))
                .filter(level -> level != null)
                .findFirst()
                .orElse(null);
    }
}
