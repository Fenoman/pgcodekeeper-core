CREATE TABLE public.orders (
    id integer NOT NULL,
    status text
);

CREATE FUNCTION public.log_status_change() RETURNS "trigger"
    LANGUAGE plpgsql
    AS $$
begin
	return NEW;
end;
$$;

CREATE TRIGGER status_change_trg
	AFTER UPDATE ON public.orders
	FOR EACH ROW
	WHEN (old.status is distinct from new.status and new.status is not null)
	EXECUTE FUNCTION public.log_status_change();
