package com.toadzip.backend.admin.dto;

import java.time.Instant;

public record AdminDataSummary(long id, String name, String subtitle, String provider, String rental,
        boolean deleted, boolean modified, boolean reviewRequired, Instant updatedAt) { }
