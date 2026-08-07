SET search_path = pg_catalog;

DROP INDEX public.idx_m;

ALTER TABLE public.t
	DROP CONSTRAINT excl_m;

CREATE INDEX idx_m ON public.t USING hash (c);

ALTER TABLE public.t
	ADD CONSTRAINT excl_m EXCLUDE USING gist (id WITH =);
