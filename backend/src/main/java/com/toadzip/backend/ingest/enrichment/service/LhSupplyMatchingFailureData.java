package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.lh.refinement.service.LhAnnouncementEnrichmentMapper.LhSupplyData;

record LhSupplyMatchingFailureData(
        LhSupplyData source,
        LhAnnouncementEnrichmentFailureReason reason,
        String detail
) {
}
