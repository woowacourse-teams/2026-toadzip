package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhCatalogSourceItem;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LhLeaseCatalogResponseParser {

    private static final String LIST_KEY = "dsList";

    public ParsedPage parse(ExternalDataResponse response) {
        List<LhCatalogSourceItem> items = DataGoKrOpenApiClient.findRows(response.body(), LIST_KEY)
                .stream()
                .map(LhCatalogSourceItem::from)
                .toList();
        return new ParsedPage(items);
    }

    public record ParsedPage(List<LhCatalogSourceItem> items) {

        public boolean completesCollection(int pageSize) {
            return items.size() < pageSize;
        }
    }
}
