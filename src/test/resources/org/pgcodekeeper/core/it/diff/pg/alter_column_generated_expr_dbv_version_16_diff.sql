SET search_path = pg_catalog;

ALTER TABLE ONLY public.t1
	DROP COLUMN col1;

ALTER TABLE public.t1
	ADD COLUMN col1 integer GENERATED ALWAYS AS (1 + 2) STORED;
