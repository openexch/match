-- Market-data schema for TimescaleDB (database: marketdata).
-- Source of truth for chart/time-series data on the market-data plane.
--
-- Applied automatically by the gateway at startup (MarketDataDb.ensureSchema):
-- whole-line comments are stripped, then statements are split on ';' and run
-- one at a time with autocommit (continuous aggregates cannot be created
-- inside a transaction block). Keep it idempotent: IF NOT EXISTS everywhere.
-- Do NOT use inline trailing comments or semicolons inside comments.
-- Do NOT edit a continuous aggregate's SELECT here: cagg definitions are
-- immutable in TimescaleDB. Changing one requires a manual drop + recreate +
-- re-materialization, documented in the PR that changes it.
--
-- The timescaledb EXTENSION is created by provisioning (superuser), never here.
--
-- Values are display-grade doubles by design: this plane mirrors the JSON
-- contract of the gateway. Authoritative money lives on the OMS plane.

CREATE TABLE IF NOT EXISTS trades (
    time        TIMESTAMPTZ      NOT NULL,
    market_id   INTEGER          NOT NULL,
    price       DOUBLE PRECISION NOT NULL,
    quantity    DOUBLE PRECISION NOT NULL,
    trade_count INTEGER          NOT NULL DEFAULT 1,
    ingest_time TIMESTAMPTZ      NOT NULL DEFAULT now()
);

SELECT create_hypertable('trades', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS trades_market_time_idx ON trades (market_id, time DESC);

-- Candles are derived in-database from the raw trade stream.
-- Level 1: 1m from trades. volume is BASE volume (sum of quantity),
-- quote_volume is price*quantity (what TICKER_STATS.volume24h reports).
-- materialized_only = false is REQUIRED on every level (TimescaleDB >= 2.13
-- defaults it to true, which silently hides the current, unmaterialized bucket).

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 minute', time) AS bucket,
       market_id,
       first(price, time)       AS open,
       max(price)               AS high,
       min(price)               AS low,
       last(price, time)        AS close,
       sum(quantity)            AS volume,
       sum(price * quantity)    AS quote_volume,
       sum(trade_count)::bigint AS trade_count
FROM trades
GROUP BY 1, 2
WITH NO DATA;

-- Hierarchical levels: each aggregates the previous level (cagg-on-cagg).
-- Every width is an integer multiple of its parent, and time_bucket's origin
-- (2000-01-03 UTC, epoch 946857600) is divisible by all six widths, so DB
-- buckets align exactly with the gateway's floor(tsMs/intervalMs)*intervalMs.

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_5m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '5 minutes', bucket) AS bucket,
       market_id,
       first(open, bucket) AS open,
       max(high)           AS high,
       min(low)            AS low,
       last(close, bucket) AS close,
       sum(volume)         AS volume,
       sum(quote_volume)   AS quote_volume,
       sum(trade_count)    AS trade_count
FROM candles_1m
GROUP BY 1, 2
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_15m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '15 minutes', bucket) AS bucket,
       market_id,
       first(open, bucket) AS open,
       max(high)           AS high,
       min(low)            AS low,
       last(close, bucket) AS close,
       sum(volume)         AS volume,
       sum(quote_volume)   AS quote_volume,
       sum(trade_count)    AS trade_count
FROM candles_5m
GROUP BY 1, 2
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1h
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 hour', bucket) AS bucket,
       market_id,
       first(open, bucket) AS open,
       max(high)           AS high,
       min(low)            AS low,
       last(close, bucket) AS close,
       sum(volume)         AS volume,
       sum(quote_volume)   AS quote_volume,
       sum(trade_count)    AS trade_count
FROM candles_15m
GROUP BY 1, 2
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_4h
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '4 hours', bucket) AS bucket,
       market_id,
       first(open, bucket) AS open,
       max(high)           AS high,
       min(low)            AS low,
       last(close, bucket) AS close,
       sum(volume)         AS volume,
       sum(quote_volume)   AS quote_volume,
       sum(trade_count)    AS trade_count
FROM candles_1h
GROUP BY 1, 2
WITH NO DATA;

CREATE MATERIALIZED VIEW IF NOT EXISTS candles_1d
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '1 day', bucket) AS bucket,
       market_id,
       first(open, bucket) AS open,
       max(high)           AS high,
       min(low)            AS low,
       last(close, bucket) AS close,
       sum(volume)         AS volume,
       sum(quote_volume)   AS quote_volume,
       sum(trade_count)    AS trade_count
FROM candles_4h
GROUP BY 1, 2
WITH NO DATA;

-- Refresh policies. end_offsets are laddered (1m < 5m < ... < 1d) so each
-- child reads an already-materialized parent region: real-time aggregation
-- covers the unmaterialized tail at query time.

SELECT add_continuous_aggregate_policy('candles_1m',
    start_offset => INTERVAL '1 hour', end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute', if_not_exists => TRUE);

SELECT add_continuous_aggregate_policy('candles_5m',
    start_offset => INTERVAL '4 hours', end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes', if_not_exists => TRUE);

SELECT add_continuous_aggregate_policy('candles_15m',
    start_offset => INTERVAL '12 hours', end_offset => INTERVAL '15 minutes',
    schedule_interval => INTERVAL '15 minutes', if_not_exists => TRUE);

SELECT add_continuous_aggregate_policy('candles_1h',
    start_offset => INTERVAL '2 days', end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '30 minutes', if_not_exists => TRUE);

SELECT add_continuous_aggregate_policy('candles_4h',
    start_offset => INTERVAL '7 days', end_offset => INTERVAL '4 hours',
    schedule_interval => INTERVAL '2 hours', if_not_exists => TRUE);

SELECT add_continuous_aggregate_policy('candles_1d',
    start_offset => INTERVAL '14 days', end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '12 hours', if_not_exists => TRUE);

-- Raw trades are pruned after 30 days. Candle aggregates are kept forever
-- (they are tiny). INVARIANT: every cagg start_offset above (max 14 days)
-- must stay well below this retention window. A refresh window that overlaps
-- dropped chunks DELETES the materialized cagg rows for that region.

SELECT add_retention_policy('trades', drop_after => INTERVAL '30 days', if_not_exists => TRUE);

-- Compression: deliberately not enabled in phase 1 (30 days of raw trades at
-- dev rates is well under 1 GB, and compressed chunks complicate late writes).
-- Follow-up if disk ever matters:
--   ALTER TABLE trades SET (timescaledb.compress, timescaledb.compress_segmentby = 'market_id', timescaledb.compress_orderby = 'time DESC')
--   SELECT add_compression_policy('trades', compress_after => INTERVAL '7 days')

-- Taker (aggressor) side of the aggregated trade bucket (schema v5).
-- NULL means the row was written before the cluster carried takerSide
-- (or arrived from a pre-v5 upstream during a mixed-version rolling update).
-- true means the taker bought (BID aggressor), false means the taker sold.
-- No continuous aggregate references this column, so an idempotent
-- ALTER at the tail is safe for both fresh and existing databases.

ALTER TABLE trades ADD COLUMN IF NOT EXISTS taker_side BOOLEAN;
