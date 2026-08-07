CREATE TABLE public.testtable (
    id bigint,
    name character varying(30)
);

CREATE VIEW public.testview (col) WITH (security_barrier) AS
    SELECT testtable.id FROM public.testtable WHERE (testtable.id > 0);
