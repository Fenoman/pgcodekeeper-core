CREATE TABLE public.testtable (
    c1 integer NOT NULL,
    c2 text
);

CREATE MATERIALIZED VIEW public.testmatview (col) AS
    SELECT testtable.c1 FROM public.testtable WHERE (testtable.c1 > 0)
WITH DATA;
