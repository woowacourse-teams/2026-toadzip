package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.RuleSetContent;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Only immutable public rules are cached. Profiles and personalized evaluation results are never
 * cached.
 */
@Component
public class RuleSetSnapshotCache {
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final RuleSetJsonCodec codec;
    private long bytes;

    public RuleSetSnapshotCache(RuleSetJsonCodec codec) {
        this.codec = codec;
    }

    public synchronized RuleSetContent read(long id, long version, String snapshot) {
        Key key = new Key(id, version);
        Entry existing = entries.get(key);
        if (existing != null) {
            return existing.content();
        }
        RuleSetContent content = codec.read(snapshot);
        long size = snapshot.getBytes(StandardCharsets.UTF_8).length;
        entries.put(key, new Entry(content, size));
        bytes += size;
        while (bytes > MAX_BYTES || entries.size() > MAX_ENTRIES) {
            Key oldest = entries.keySet().iterator().next();
            bytes -= entries.remove(oldest).size();
        }
        return content;
    }

    private record Key(long id, long version) {}

    private record Entry(RuleSetContent content, long size) {}
}
