CREATE TABLE public.audited (
	id integer,
	payload text
);

CREATE FUNCTION public.audit_fn() RETURNS trigger
    LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END; $$;

CREATE TRIGGER t_audit_insert
	BEFORE INSERT ON public.audited
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE TRIGGER t_audit_update
	BEFORE UPDATE ON public.audited
	FOR EACH ROW
	EXECUTE PROCEDURE public.audit_fn();

CREATE TABLE public.plain (
	id integer
);
