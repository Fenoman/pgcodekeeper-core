CREATE FUNCTION public.parent_payload_atomic(p_id integer)
RETURNS text
LANGUAGE sql
BEGIN ATOMIC
    SELECT payload
    FROM public.parent
    WHERE id = p_id;
END;
