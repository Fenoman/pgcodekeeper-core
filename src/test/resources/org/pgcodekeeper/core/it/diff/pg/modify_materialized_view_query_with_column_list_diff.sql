SET search_path = pg_catalog;

DROP MATERIALIZED VIEW public.testmatview;

CREATE MATERIALIZED VIEW public.testmatview (col) AS
	SELECT testtable.c1 FROM public.testtable WHERE (testtable.c1 > 0)
WITH DATA;
