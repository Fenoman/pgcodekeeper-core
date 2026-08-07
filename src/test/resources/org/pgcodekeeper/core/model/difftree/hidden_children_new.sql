CREATE TABLE public.only_hidden (
	id integer,
	payload text
);

CREATE TABLE public.own_change (
	id integer,
	added_later text
);

CREATE TABLE public.visible_child (
	id integer,
	payload text
);

CREATE TABLE public.reordered (
	payload text,
	id integer
);

CREATE FUNCTION public.audit_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END; $$;
