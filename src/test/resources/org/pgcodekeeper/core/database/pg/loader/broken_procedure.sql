CREATE OR REPLACE PROCEDURE public.lock_and_agg_items(p_id_1 integer, p_id_2 integer)
    LANGUAGE plpgsql
    AS $$
DECLARE
    _rows public.treat[];
BEGIN
    WITH locked_rows AS (
        SELECT *
        FROM public.treat
        WHERE id IN (p_id_1, p_id_2)
        FOR UPDATE
    )
    SELECT array_agg(x::public.treat)
    INTO _rows
    FROM locked_rows x;
END;
$$;