CREATE TABLE public.bookings (
    id bigint NOT NULL,
    room integer,
    period tsrange,
    active boolean
);

ALTER TABLE public.bookings
    ADD CONSTRAINT bookings_no_overlap EXCLUDE USING gist (room WITH =, period WITH &&) WHERE (room is not null and active);
