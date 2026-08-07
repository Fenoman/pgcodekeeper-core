CREATE FUNCTION public.first_rows()
RETURNS TABLE(first_id integer, first_text text)
LANGUAGE sql
AS $$ SELECT 1, 'one'::text $$;

CREATE FUNCTION public.second_rows()
RETURNS TABLE(second_amount numeric)
LANGUAGE sql
AS $$ SELECT 2::numeric $$;

CREATE VIEW public.rows_from_explicit AS
SELECT *
FROM ROWS FROM (
    public.first_rows(),
    public.second_rows()
) AS r(renamed_id, renamed_text);

CREATE VIEW public.rows_from_unaliased AS
SELECT *
FROM ROWS FROM (
    public.first_rows(),
    public.second_rows()
) WITH ORDINALITY;

CREATE VIEW public.rows_from_unaliased_qualified AS
SELECT first_rows.first_id AS first_value,
       first_rows.second_amount AS second_value,
       first_rows.ordinality AS row_number
FROM ROWS FROM (
    public.first_rows(),
    public.second_rows()
) WITH ORDINALITY;
