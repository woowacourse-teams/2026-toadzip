package com.toadzip.backend.seo.repository;

import java.time.OffsetDateTime;

public record SeoSitemapEntry(long id, OffsetDateTime lastModified) {
}
