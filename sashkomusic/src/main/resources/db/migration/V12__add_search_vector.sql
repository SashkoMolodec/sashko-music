CREATE EXTENSION IF NOT EXISTS unaccent;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'simple_unaccent') THEN
        CREATE TEXT SEARCH CONFIGURATION simple_unaccent (COPY = simple);
        ALTER TEXT SEARCH CONFIGURATION simple_unaccent
            ALTER MAPPING FOR word, hword, hword_part WITH unaccent, simple;
    END IF;
END$$;

ALTER TABLE releases ADD COLUMN IF NOT EXISTS search_vector tsvector;

CREATE INDEX IF NOT EXISTS idx_releases_search_vector ON releases USING GIN (search_vector);
