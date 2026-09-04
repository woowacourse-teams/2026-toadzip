package com.toadzip.backend.announcement.dto.response;

import java.math.BigDecimal;

public record CompetitionResponse(BigDecimal actualRate, BigDecimal predictedRate) {
}
