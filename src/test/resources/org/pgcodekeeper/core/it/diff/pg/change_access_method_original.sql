SET search_path = pg_catalog;

CREATE TABLE public.t (
    id integer,
    c text
);

CREATE INDEX idx_m ON public.t (c);

ALTER TABLE public.t
    ADD CONSTRAINT excl_m EXCLUDE (id WITH =);
