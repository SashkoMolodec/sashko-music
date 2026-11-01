-- A freshly processed release waits for the user to pick its sub-library; audio analysis and the
-- Apple Music sync start only after that, on the final path. Existing releases are already placed.
ALTER TABLE releases
    ADD COLUMN placement_pending BOOLEAN NOT NULL DEFAULT FALSE;
