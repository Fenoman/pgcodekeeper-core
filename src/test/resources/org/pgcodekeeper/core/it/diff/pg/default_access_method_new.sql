SET search_path = pg_catalog;

CREATE TABLE public.t (
    id integer,
    c text
);

-- the same two indexes with the two spellings exchanged
CREATE INDEX idx_written ON public.t (c);

CREATE INDEX idx_omitted ON public.t USING btree (id);

ALTER TABLE public.t
    ADD CONSTRAINT excl_written EXCLUDE (id WITH =);

ALTER TABLE public.t
    ADD CONSTRAINT excl_omitted EXCLUDE USING btree (id WITH <>);
