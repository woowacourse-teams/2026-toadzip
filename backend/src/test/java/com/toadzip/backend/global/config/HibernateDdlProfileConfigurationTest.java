package com.toadzip.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class HibernateDdlProfileConfigurationTest {

    private static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void 로컬과_개발_환경에서도_스키마를_검증만_한다() throws IOException {
        assertThat(ddlAutoOf("local")).isEqualTo("validate");
        assertThat(ddlAutoOf("dev")).isEqualTo("validate");
    }

    @Test
    void 운영_환경에서는_스키마를_검증만_한다() throws IOException {
        assertThat(ddlAutoOf("prod")).isEqualTo("validate");
    }

    private Object ddlAutoOf(String profile) throws IOException {
        ClassPathResource resource = new ClassPathResource("application-" + profile + ".yml");
        return loader.load(profile, resource)
                .stream()
                .map(propertySource -> propertySource.getProperty(DDL_AUTO))
                .filter(ddlAuto -> ddlAuto != null)
                .findFirst()
                .orElse(null);
    }
}
