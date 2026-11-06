-- Ticketing schema. Free-text columns are limited to 200 characters (mirrors TextRules in the domain). All timestamps are TIMESTAMPTZ and written in UTC.
-- Foreign keys use the default NO ACTION: nothing in this module is deleted in cascade.
-- Postgres does not index foreign key columns automatically, hence the explicit *_idx indexes.

CREATE TABLE event(
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> '' AND char_length(name) <= 200),
    starts_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OnSale', 'Cancelled')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE event_section(
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event (id),
    name TEXT NOT NULL CHECK (btrim(name) <> '' AND char_length(name) <= 200),
    price_minor BIGINT NOT NULL CHECK (price_minor > 0),
    currency TEXT NOT NULL CHECK (currency IN ('PLN', 'EUR', 'USD')),
    CONSTRAINT event_section_event_id_name_key UNIQUE (event_id, name)
);

CREATE TABLE event_seat(
    id UUID PRIMARY KEY,
    section_id UUID NOT NULL REFERENCES event_section (id),
    label TEXT NOT NULL CHECK (btrim(label) <> '' AND char_length(label) <= 200),
    CONSTRAINT event_seat_section_id_label_key UNIQUE (section_id, label)
);

-- Named ticket_order because ORDER is a reserved word.
CREATE TABLE ticket_order(
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event (id),
    customer_id UUID NOT NULL REFERENCES "user" (id),
    created_at TIMESTAMPTZ NOT NULL
);

-- seat_label and price/currency are copied from the seat/section at hold time, so a later price or
-- label change on the event does not alter tickets that were already issued.
CREATE TABLE ticket(
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES ticket_order (id),
    seat_id UUID NOT NULL REFERENCES event_seat (id),
    customer_id UUID NOT NULL REFERENCES "user" (id),
    seat_label TEXT NOT NULL CHECK (btrim(seat_label) <> '' AND char_length(seat_label) <= 200),
    price_minor BIGINT NOT NULL CHECK (price_minor > 0),
    currency TEXT NOT NULL CHECK (currency IN ('PLN', 'EUR', 'USD')),
    status TEXT NOT NULL CHECK (status IN ('Held', 'Paid', 'Cancelled')),
    held_until TIMESTAMPTZ NOT NULL,
    charge_id TEXT CHECK (btrim(charge_id) <> '' AND char_length(charge_id) <= 200),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Mirrors the domain invariant of Ticket: Paid exactly when both charge_id and paid_at are set.
    CONSTRAINT ticket_paid_iff_charged_chk CHECK ((status = 'Paid') = (charge_id IS NOT NULL AND paid_at IS NOT NULL))
);

-- At most one live (Held or Paid) ticket per seat. Cancelled tickets stay as history and free the seat.
-- This index, not an application-level check, is what prevents double booking under concurrent holds.
CREATE UNIQUE INDEX ticket_live_seat_uidx ON ticket (seat_id) WHERE status IN ('Held', 'Paid');

-- A payment provider charge settles exactly one ticket.
CREATE UNIQUE INDEX ticket_charge_id_uidx ON ticket (charge_id) WHERE charge_id IS NOT NULL;

-- Serves the hold-expiry task (status = 'Held' AND held_until <= now).
CREATE INDEX ticket_held_until_held_idx ON ticket (held_until) WHERE status = 'Held';

-- event_section.event_id and event_seat.section_id are already the leading columns of their UNIQUE
-- constraints' indexes, so they need no separate FK index.
-- ticket_live_seat_uidx is partial (live tickets only), so seat_id still gets a full index for FK checks.
-- The customer/event FK indexes below are deliberate: Postgres does not index FK columns, and these serve joins and
-- orphan checks (deleting a referenced row scans the referencing table).
CREATE INDEX ticket_seat_id_idx ON ticket (seat_id);
CREATE INDEX ticket_order_event_id_idx ON ticket_order (event_id);
CREATE INDEX ticket_order_customer_id_idx ON ticket_order (customer_id);
CREATE INDEX ticket_order_fk_idx ON ticket (order_id);
CREATE INDEX ticket_customer_id_idx ON ticket (customer_id);
