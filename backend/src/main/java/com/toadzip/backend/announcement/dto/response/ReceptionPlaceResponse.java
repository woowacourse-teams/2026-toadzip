package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.ReceptionMethod;

public record ReceptionPlaceResponse(
        String name,
        ReceptionMethod method,
        String address,
        String phoneNumber,
        String url
) {
}
