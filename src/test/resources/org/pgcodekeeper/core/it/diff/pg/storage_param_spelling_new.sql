SET search_path = pg_catalog;

-- the same parameters as a hand-written file states them
CREATE TABLE public.t (
    id integer,
    c text,
    CONSTRAINT pk_t PRIMARY KEY (id) WITH (fillfactor=70)
)
WITH (FILLFACTOR=70, autovacuum_enabled=TRUE, toast.autovacuum_enabled=False);

CREATE INDEX idx_t ON public.t USING btree (c) WITH (fillfactor=70);

ALTER TABLE ONLY public.t
    ALTER COLUMN id SET (n_distinct=100);

CREATE VIEW public.v WITH (security_barrier=True) AS
    SELECT t.id
    FROM public.t;
