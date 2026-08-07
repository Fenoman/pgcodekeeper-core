CREATE TABLE public.audited (
	id integer,
	payload text
);

CREATE FUNCTION public.audit_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END; $$;

CREATE TABLE public.plain (
	id integer,
	added_later text
);
