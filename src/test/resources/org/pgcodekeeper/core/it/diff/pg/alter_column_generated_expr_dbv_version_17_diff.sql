SET search_path = pg_catalog;

ALTER TABLE ONLY public.t1
	ALTER COLUMN col1 SET EXPRESSION AS (1 + 2);
