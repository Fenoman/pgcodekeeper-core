SET search_path = pg_catalog;

-- the shape the database side takes: every non-identifier value quoted
CREATE TABLE public.t (
    id integer,
    c text,
    CONSTRAINT pk_t PRIMARY KEY (id) WITH (fillfactor='70')
)
WITH (fillfactor='70', autovacuum_enabled='true', toast.autovacuum_enabled='false');

CREATE INDEX idx_t ON public.t USING btree (c) WITH (fillfactor='70');

ALTER TABLE ONLY public.t
    ALTER COLUMN id SET (n_distinct='100');

CREATE VIEW public.v WITH (security_barrier='true') AS
    SELECT t.id
    FROM public.t;
