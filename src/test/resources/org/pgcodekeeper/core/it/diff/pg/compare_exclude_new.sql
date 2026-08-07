CREATE TABLE public.bookings (
    id bigint NOT NULL,
    room integer,
    period tsrange,
    active boolean
);

ALTER TABLE public.bookings
    ADD CONSTRAINT bookings_no_overlap EXCLUDE USING gist (room WITH =, period WITH &&)
    where ( room is NOT NULL AND active );
