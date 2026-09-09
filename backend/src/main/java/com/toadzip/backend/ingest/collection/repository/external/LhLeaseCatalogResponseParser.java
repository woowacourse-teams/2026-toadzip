package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhCatalogSourceItem;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LhLeaseCatalogResponseParser {

    private static final String LIST_KEY = "dsList";

    public ExternalDataPage<LhCatalogSourceItem> parse(ExternalDataResponse response) {
        List<LhCatalogSourceItem> items = ExternalResponseRows.find(response.body(), LIST_KEY)
                .stream()
                .map(LhCatalogSourceItem::from)
                .toList();
        return new ExternalDataPage<>(items, -1);
    }

}
