SET search_path = pg_catalog;

DROP VIEW public.testview;

CREATE VIEW public.testview (col) AS
	SELECT testtable.id FROM public.testtable WHERE (testtable.id > 0);
