CREATE TABLE smartlists (
    id                BIGSERIAL PRIMARY KEY,
    name              TEXT        NOT NULL UNIQUE,
    dsl               JSONB       NOT NULL,
    last_generated_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE smartlists IS 'Dynamic playlists with DSL rules over track tags. M3U file regenerated on track changes.';
