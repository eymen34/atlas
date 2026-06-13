-- V14 — full-text search over tickets (T-028). A STORED generated tsvector column +
-- GIN index. No new table, no new entity (the Ticket entity is UNCHANGED — search_doc
-- is deliberately NOT mapped, per tsvector_not_on_entity / entity_appcds_hard_rule).
--
-- The tsvector is GENERATED ALWAYS AS (...) STORED: Postgres computes + backfills it for
-- every existing row at migration time and maintains it on every write. English is
-- hardcoded in the expression (search_settings + SEARCH_LANGUAGE are out of scope).
--
-- The ts_headline call in TicketSearchRepositoryImpl re-builds the SAME document
-- expression — coalesce(title,'') || ' ' || coalesce(description,'') — so highlighted
-- fragments stay consistent with what was indexed. Keep the two expressions identical.
--
-- A raw INSERT that lists search_doc is rejected by Postgres (cannot write a generated
-- column) — asserted by an IT (AC-1.4).

ALTER TABLE tickets
    ADD COLUMN search_doc tsvector GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED;

CREATE INDEX ix_tickets_search_doc ON tickets USING GIN (search_doc);
