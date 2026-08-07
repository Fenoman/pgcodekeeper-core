CREATE FUNCTION public.parent_payload(p_id integer)
RETURNS text
LANGUAGE sql
AS $function$
    SELECT payload
    FROM public.parent
    WHERE id = p_id
$function$;
