package com.toadzip.backend.ingest.collection.repository.external;

import tools.jackson.databind.JsonNode;

public interface ExternalDataResponseStatusValidator {

    void validate(JsonNode root);
}
