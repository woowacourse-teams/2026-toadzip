CREATE TABLE ingest_execution_ownership (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    owner_id UUID
);

INSERT INTO ingest_execution_ownership (id, generation, owner_id) VALUES (1, 0, NULL);
