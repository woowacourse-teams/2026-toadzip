package com.toadzip.backend.ingest.collection.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record ExternalDataIngestProperties(String serviceKey, BaseUrl baseUrl) {

    /** 공공데이터포털 수집 키가 설정됐는지 본다. 화면과 시작 전 확인은 이 여부만 쓰고 키 값은 내보내지 않는다. */
    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    public record BaseUrl(String myhomeComplex, String myhomeAnnouncement, String lh) {
    }
}
