CREATE SCHEMA app;

CREATE TYPE app.item AS (
    id integer
);

CREATE FUNCTION app.collect_items() RETURNS app.item[]
LANGUAGE plpgsql
AS $function$
DECLARE
    _rows app.item[];
BEGIN
    WITH locked_rows AS (SELECT 1)
    SELECT array_agg(x::app.item) INTO _rows
    FROM locked_rows x;
    RETURN _rows;
END
$function$;
