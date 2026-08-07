SET search_path = pg_catalog;

DROP VIEW public.testview;

CREATE VIEW public.testview (col_b) AS
	SELECT testtable.id FROM public.testtable;
