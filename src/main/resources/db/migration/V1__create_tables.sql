CREATE TABLE outbox_events
(
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  VARCHAR(255) NOT NULL,
    event_type    VARCHAR(255) NOT NULL,
    event_payload TEXT         NOT NULL,
    processed     BOOLEAN               DEFAULT FALSE,
    processed_at  TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(255)   NOT NULL,
    description    TEXT,
    category       VARCHAR(255)   NOT NULL,
    price          DECIMAL(10, 2) NOT NULL,
    brand          VARCHAR(255)   NOT NULL,
    sku            VARCHAR(255)   NOT NULL UNIQUE,
    specifications TEXT,
    tags           TEXT,
    created_by     VARCHAR(255)   NOT NULL,
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP,
    version        BIGINT                  DEFAULT 0
);

CREATE INDEX idx_outbox_events_processed_created_at ON outbox_events (processed, created_at);
CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_brand ON products (brand);