CREATE FUNCTION public.bad_body()
RETURNS integer
LANGUAGE plpgsql
AS $function$
BEGIN
    RETURN (;
END
$function$;
