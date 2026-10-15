-- STAGING ONLY in use: GetMore's Magento 2.4 store simulated in process (loans.getmore.mode=SIMULATED). The tables exist
-- in every cell and stay empty unless the simulator is on (the rule is seeded at boot in SIMULATED, never here).
-- Shapes and signs follow Magento: order and credit-memo discounts are NEGATIVE, discount_canceled positive,
-- amounts NUMERIC(12,4). Nothing here is money.
CREATE TABLE getmore_sim_rules (
    rule_id               BIGINT        NOT NULL,
    name                  VARCHAR(255)  NOT NULL,
    is_active             BOOLEAN       NOT NULL,
    from_date             DATE,
    to_date               DATE,
    simple_action         VARCHAR(16)   NOT NULL,
    discount_amount       NUMERIC(12,4) NOT NULL,
    coupon_type           VARCHAR(16)   NOT NULL,
    use_auto_generation   BOOLEAN       NOT NULL,
    uses_per_coupon       INTEGER       NOT NULL,
    uses_per_customer     INTEGER       NOT NULL,
    stop_rules_processing BOOLEAN       NOT NULL,
    apply_to_shipping     BOOLEAN       NOT NULL,
    times_used            INTEGER       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP(6)  NOT NULL,
    CONSTRAINT getmore_sim_rules_pkey PRIMARY KEY (rule_id),
    CONSTRAINT ck_getmore_sim_rules_action
        CHECK (simple_action IN ('by_percent', 'by_fixed', 'cart_fixed', 'buy_x_get_y')),
    CONSTRAINT ck_getmore_sim_rules_coupon_type CHECK (coupon_type IN ('NO_COUPON', 'SPECIFIC_COUPON', 'AUTO')),
    CONSTRAINT ck_getmore_sim_rules_uses CHECK (uses_per_coupon >= 0 AND uses_per_customer >= 0 AND times_used >= 0)
);

CREATE TABLE getmore_sim_stores (
    store_id    SERIAL       NOT NULL,
    outlet_code VARCHAR(64)  NOT NULL,
    outlet_name VARCHAR(120) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT getmore_sim_stores_pkey PRIMARY KEY (store_id),
    CONSTRAINT uq_getmore_sim_stores_outlet UNIQUE (outlet_code)
);

CREATE TABLE getmore_sim_coupons (
    coupon_id          BIGSERIAL     NOT NULL,
    rule_id            BIGINT        NOT NULL,
    code               VARCHAR(255)  NOT NULL,
    usage_limit        INTEGER,
    usage_per_customer INTEGER,
    times_used         INTEGER       NOT NULL DEFAULT 0,
    expiration_date    TIMESTAMP(6),
    is_primary         BOOLEAN       NOT NULL DEFAULT FALSE,
    type               INTEGER       NOT NULL,
    created_at         TIMESTAMP(6)  NOT NULL,
    -- SIMULATOR ASSUMPTION A1, not a Magento column: what the coupon is worth, spent in parts (GetMore G5/G6).
    allowance          NUMERIC(12,4),
    allowance_currency VARCHAR(3),
    deleted_at         TIMESTAMP(6),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT getmore_sim_coupons_pkey PRIMARY KEY (coupon_id),
    CONSTRAINT fk_getmore_sim_coupons_rule FOREIGN KEY (rule_id) REFERENCES getmore_sim_rules (rule_id),
    CONSTRAINT ck_getmore_sim_coupons_type CHECK (type IN (0, 1) AND times_used >= 0),
    CONSTRAINT ck_getmore_sim_coupons_allowance
        CHECK ((allowance IS NULL) = (allowance_currency IS NULL)
            AND (allowance IS NULL OR allowance > 0)
            AND (allowance_currency IS NULL OR allowance_currency ~ '^[A-Z]{3}$'))
);
-- Magento keeps a code unique across every rule, compared without case.
CREATE UNIQUE INDEX uq_getmore_sim_coupons_code ON getmore_sim_coupons (upper(code));

CREATE SEQUENCE getmore_sim_order_number_seq START WITH 1;
CREATE TABLE getmore_sim_orders (
    entity_id           BIGSERIAL     NOT NULL,
    increment_id        VARCHAR(32)   NOT NULL,
    sale_reference      VARCHAR(64)   NOT NULL,   -- the test till's reference: the sale action is idempotent on it
    coupon_code         VARCHAR(255),
    applied_rule_ids    VARCHAR(255),
    store_id            INTEGER       NOT NULL,
    store_name          VARCHAR(255)  NOT NULL,
    state               VARCHAR(32)   NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    order_currency_code VARCHAR(3)    NOT NULL,
    base_currency_code  VARCHAR(3)    NOT NULL,
    subtotal            NUMERIC(12,4) NOT NULL,
    grand_total         NUMERIC(12,4) NOT NULL,
    discount_amount     NUMERIC(12,4) NOT NULL,
    discount_invoiced   NUMERIC(12,4) NOT NULL DEFAULT 0,
    discount_refunded   NUMERIC(12,4) NOT NULL DEFAULT 0,
    discount_canceled   NUMERIC(12,4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT getmore_sim_orders_pkey PRIMARY KEY (entity_id),
    CONSTRAINT uq_getmore_sim_orders_increment UNIQUE (increment_id),
    CONSTRAINT uq_getmore_sim_orders_sale UNIQUE (sale_reference),
    CONSTRAINT fk_getmore_sim_orders_store FOREIGN KEY (store_id) REFERENCES getmore_sim_stores (store_id),
    CONSTRAINT ck_getmore_sim_orders_signs
        CHECK (discount_amount < 0 AND discount_invoiced <= 0 AND discount_invoiced >= discount_amount
            AND discount_refunded <= 0 AND discount_refunded >= discount_invoiced AND discount_canceled >= 0
            AND subtotal >= 0 AND grand_total >= 0),
    CONSTRAINT ck_getmore_sim_orders_state CHECK (state IN ('processing', 'complete', 'closed', 'canceled'))
);
CREATE INDEX ix_getmore_sim_orders_coupon ON getmore_sim_orders (upper(coupon_code), entity_id);
CREATE INDEX ix_getmore_sim_orders_created ON getmore_sim_orders (created_at, entity_id);

CREATE SEQUENCE getmore_sim_creditmemo_number_seq START WITH 1;
CREATE TABLE getmore_sim_creditmemos (
    entity_id        BIGSERIAL     NOT NULL,
    increment_id     VARCHAR(32)   NOT NULL,
    refund_reference VARCHAR(64)   NOT NULL,   -- given, or SIM-REFUND-<uuid>
    order_id         BIGINT        NOT NULL,
    state            INTEGER       NOT NULL,   -- 1 open, 2 refunded, 3 canceled
    discount_amount  NUMERIC(12,4) NOT NULL,
    subtotal         NUMERIC(12,4) NOT NULL,
    grand_total      NUMERIC(12,4) NOT NULL,
    created_at       TIMESTAMP(6)  NOT NULL,
    CONSTRAINT getmore_sim_creditmemos_pkey PRIMARY KEY (entity_id),
    CONSTRAINT uq_getmore_sim_creditmemos_increment UNIQUE (increment_id),
    CONSTRAINT uq_getmore_sim_creditmemos_reference UNIQUE (refund_reference),
    CONSTRAINT fk_getmore_sim_creditmemos_order FOREIGN KEY (order_id) REFERENCES getmore_sim_orders (entity_id),
    CONSTRAINT ck_getmore_sim_creditmemos CHECK (state IN (1, 2, 3) AND discount_amount < 0
        AND subtotal >= 0 AND grand_total >= 0)
);
CREATE INDEX ix_getmore_sim_creditmemos_order ON getmore_sim_creditmemos (order_id, entity_id);
