CREATE TYPE public."treat" AS (
    id integer,
    name text,
    value numeric,
    created_at timestamp
);

CREATE OR REPLACE PROCEDURE public.with_unquoted_token(p_id_1 integer, p_id_2 integer)
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

CREATE OR REPLACE PROCEDURE public.with_quoted_token(p_id_1 integer, p_id_2 integer)
    LANGUAGE plpgsql
    AS $$
DECLARE
    _rows public."treat"[];
BEGIN
    WITH locked_rows AS (
        SELECT *
        FROM public."treat"
        WHERE id IN (p_id_1, p_id_2)
        FOR UPDATE
    )
    SELECT array_agg(x::public."treat")
    INTO _rows
    FROM locked_rows x;
END;
$$;