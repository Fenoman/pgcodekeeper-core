SET search_path = pg_catalog;

CREATE OR REPLACE FUNCTION public.f() RETURNS integer
    LANGUAGE plpgsql
    AS $$
BEGIN
    RETURN (SELECT count(*) FROM public.v);
END;
$$;

CREATE VIEW public.v AS
	SELECT public.f() AS val;
