CREATE TABLE public.testtable (
    id bigint,
    name character varying(30)
);

CREATE VIEW public.testview (col_b) AS
    SELECT testtable.id FROM public.testtable;
