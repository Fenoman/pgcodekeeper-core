SET search_path = pg_catalog;

CREATE TABLE public.t (
    id integer,
    c text
);

-- the shape pg_get_indexdef always writes: the method is spelled out
CREATE INDEX idx_written ON public.t USING btree (c);

-- the shape a hand-written project file takes: the method is left to the server
CREATE INDEX idx_omitted ON public.t (id);

ALTER TABLE public.t
    ADD CONSTRAINT excl_written EXCLUDE USING btree (id WITH =);

ALTER TABLE public.t
    ADD CONSTRAINT excl_omitted EXCLUDE (id WITH <>);
