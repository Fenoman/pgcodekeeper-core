CREATE FUNCTION public.parent_exists(p_id integer)
RETURNS boolean
LANGUAGE sql
RETURN EXISTS (
    SELECT 1
    FROM public.parent
    WHERE id = p_id
);
