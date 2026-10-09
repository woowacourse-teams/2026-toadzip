package com.toadzip.backend.privacy.repository;

import com.toadzip.backend.privacy.domain.PrivacyHash;
import com.toadzip.backend.privacy.domain.PrivacyNotice;
import com.toadzip.backend.privacy.exception.PrivacyException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

@Repository
public class PrivacyNoticeCatalog {

    private final List<PrivacyNotice> documents;
    private final Map<String, String> currentVersions;

    public PrivacyNoticeCatalog() {
        Properties manifest = new Properties();
        try (var reader = new InputStreamReader(new ClassPathResource("privacy/manifest.properties").getInputStream(),
                StandardCharsets.UTF_8)) {
            manifest.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Privacy manifest cannot be loaded", exception);
        }
        documents = Arrays.stream(manifest.getProperty("documents").split(","))
                .map(version -> load(manifest, version))
                .sorted(Comparator.comparing(PrivacyNotice::effectiveAt)).toList();
        currentVersions = manifest.stringPropertyNames().stream().filter(name -> name.startsWith("current."))
                .collect(Collectors.toUnmodifiableMap(name -> name.substring("current.".length()),
                        manifest::getProperty));
        currentVersions.forEach(this::find);
    }

    private PrivacyNotice load(Properties manifest, String version) {
        String key = manifest.getProperty(version + ".key");
        try {
            String content = new ClassPathResource("privacy/" + version + ".md")
                    .getContentAsString(StandardCharsets.UTF_8);
            String hash = PrivacyHash.sha256(content);
            if (!hash.equals(manifest.getProperty(version + ".contentHash"))) {
                throw new IllegalStateException("Privacy document differs from its immutable manifest: " + key);
            }
            return new PrivacyNotice(key, version, manifest.getProperty(version + ".scopeVersion"),
                    Instant.parse(manifest.getProperty(version + ".effectiveAt")), hash, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Privacy document cannot be loaded: " + key, exception);
        }
    }

    public List<PrivacyNotice> currentDocuments() {
        return currentVersions.keySet().stream().sorted().map(this::current).toList();
    }

    public PrivacyNotice current(String key) {
        return find(key, currentVersions.get(key));
    }

    public PrivacyNotice find(String key, String version) {
        return documents.stream().filter(document -> document.key().equals(key) && document.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new PrivacyException("PRIVACY_NOTICE_NOT_FOUND", "안내문을 찾을 수 없습니다."));
    }

    public String currentVersion(String key) {
        return current(key).version();
    }

    public boolean isCurrentVersion(String key, String version) {
        return currentVersion(key).equals(version);
    }

    public String requiredAnalyticsScope() {
        return current("ANALYTICS_NOTICE").scopeVersion();
    }

    public Instant analyticsScopeEffectiveAt() {
        return documents.stream().filter(document -> document.key().equals("ANALYTICS_NOTICE"))
                .filter(document -> requiredAnalyticsScope().equals(document.scopeVersion()))
                .map(PrivacyNotice::effectiveAt).min(Comparator.naturalOrder()).orElseThrow();
    }

    public Instant scopeInvalidatedAt(String scopeVersion) {
        Instant introduced = documents.stream().filter(document -> document.key().equals("ANALYTICS_NOTICE"))
                .filter(document -> scopeVersion.equals(document.scopeVersion()))
                .map(PrivacyNotice::effectiveAt).min(Comparator.naturalOrder()).orElse(Instant.MIN);
        return documents.stream().filter(document -> document.key().equals("ANALYTICS_NOTICE"))
                .filter(document -> !scopeVersion.equals(document.scopeVersion()))
                .map(PrivacyNotice::effectiveAt).filter(time -> time.isAfter(introduced))
                .min(Comparator.naturalOrder()).orElseGet(this::analyticsScopeEffectiveAt);
    }

    public Map<String, Instant> scopeInvalidations() {
        return documents.stream().filter(document -> document.key().equals("ANALYTICS_NOTICE"))
                .map(PrivacyNotice::scopeVersion).distinct().filter(scope -> !requiredAnalyticsScope().equals(scope))
                .collect(Collectors.toUnmodifiableMap(scope -> scope, this::scopeInvalidatedAt));
    }
}
