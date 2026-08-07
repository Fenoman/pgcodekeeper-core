CREATE TABLE public.only_hidden (
	id integer,
	payload text
);

CREATE TABLE public.own_change (
	id integer
);

CREATE TABLE public.visible_child (
	id integer,
	payload text
);

CREATE TABLE public.reordered (
	id integer,
	payload text
);

CREATE FUNCTION public.audit_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END; $$;

CREATE TRIGGER t_hidden
	BEFORE INSERT ON public.only_hidden
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE TRIGGER t_hidden
	BEFORE INSERT ON public.own_change
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE TRIGGER t_hidden
	BEFORE INSERT ON public.visible_child
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE TRIGGER t_hidden
	BEFORE INSERT ON public.reordered
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE INDEX i_visible ON public.visible_child USING btree (payload);
