SET search_path = pg_catalog;

CREATE TABLE public.t (
    id integer,
    c text
);

CREATE INDEX idx_m ON public.t USING hash (c);

ALTER TABLE public.t
    ADD CONSTRAINT excl_m EXCLUDE USING gist (id WITH =);
