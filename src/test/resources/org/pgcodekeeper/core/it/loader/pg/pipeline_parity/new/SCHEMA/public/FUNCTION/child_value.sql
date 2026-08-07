CREATE FUNCTION public.child_value(p_id integer)
RETURNS integer
LANGUAGE plpgsql
AS $function$
DECLARE
    result integer;
BEGIN
    SELECT child_value INTO result
    FROM public.child
    WHERE id = p_id;
    RETURN result;
END
$function$;
