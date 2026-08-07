CREATE TABLE public.tsub (
    c1 integer,
    c2 text,
    c3 double precision
);

ALTER TABLE public.tsub OWNER TO shamsutdinov_lr;

CREATE TABLE public.tjoin (
    col1 integer,
    col2 character(64)
);

ALTER TABLE public.tjoin OWNER TO shamsutdinov_lr;

--------------------------------------------------------------------------------

-- FROM subquery without an alias is allowed since PostgreSQL 16;
-- its output columns stay visible unqualified in the containing query

CREATE VIEW public.unnamed_subquery_view1 AS
    SELECT c1, c2
   FROM (SELECT c1, c2 FROM public.tsub);

ALTER VIEW public.unnamed_subquery_view1 OWNER TO shamsutdinov_lr;

CREATE VIEW public.unnamed_subquery_view2 AS
    SELECT *
   FROM (SELECT c1, c3 FROM public.tsub);

ALTER VIEW public.unnamed_subquery_view2 OWNER TO shamsutdinov_lr;

CREATE VIEW public.unnamed_subquery_view3 AS
    SELECT c1, col2
   FROM (SELECT c1, c3 FROM public.tsub)
     JOIN public.tjoin j ON (j.col1 = c1);

ALTER VIEW public.unnamed_subquery_view3 OWNER TO shamsutdinov_lr;
