CREATE SCHEMA routine_oracle;
CREATE SCHEMA "Routine Oracle";

CREATE TABLE routine_oracle.dep(id integer);

CREATE FUNCTION routine_oracle.ordered(p_id integer DEFAULT 41) RETURNS integer
LANGUAGE sql
SET search_path TO 'routine_oracle', 'pg_catalog'
AS $ordered$
SELECT dep.id + p_id
FROM routine_oracle.dep dep
WHERE dep.id = p_id
$ordered$;

CREATE FUNCTION routine_oracle.plpgsql_config(p_id integer) RETURNS integer
LANGUAGE plpgsql
SET search_path TO 'routine_oracle', 'pg_catalog'
AS $plpgsql$
BEGIN
    RETURN (SELECT dep.id FROM routine_oracle.dep dep WHERE dep.id = p_id);
END
$plpgsql$;

CREATE PROCEDURE routine_oracle.process_one(p_id integer)
LANGUAGE plpgsql
AS $procedure$
BEGIN
    PERFORM dep.id FROM routine_oracle.dep dep WHERE dep.id = p_id;
END
$procedure$;

CREATE FUNCTION routine_oracle.aggregate_state(state integer, value integer) RETURNS integer
LANGUAGE sql IMMUTABLE
AS 'SELECT COALESCE(state, 0) + value';

CREATE AGGREGATE routine_oracle.aggregate_sum(integer) (
    SFUNC = routine_oracle.aggregate_state,
    STYPE = integer,
    INITCOND = '0'
);

CREATE FUNCTION routine_oracle.atomic_lookup(p_id integer) RETURNS integer
LANGUAGE sql
BEGIN ATOMIC
    SELECT dep.id
    FROM routine_oracle.dep dep
    WHERE dep.id = p_id;
END;

CREATE FUNCTION routine_oracle.internal_abs(integer) RETURNS integer
LANGUAGE internal STRICT
AS 'int4abs';

CREATE FUNCTION routine_oracle.overloaded(int) RETURNS integer
LANGUAGE sql
AS 'SELECT $1 + 1';

CREATE FUNCTION routine_oracle.overloaded(text) RETURNS integer
LANGUAGE sql
AS 'SELECT length($1)';

CREATE FUNCTION "Routine Oracle"."Mixed Routine"(integer) RETURNS integer
LANGUAGE sql
AS 'SELECT $1 + 2';

CREATE FUNCTION routine_oracle.utf8_body() RETURNS text
LANGUAGE sql
AS $utf8$
SELECT 'Привет 😀 $function$'/*<CR>*/
    || E'\nвторая строка'
$utf8$;

SET check_function_bodies = off;
CREATE FUNCTION routine_oracle.bad_sql() RETURNS integer
LANGUAGE sql AS 'SELECT )';
CREATE FUNCTION routine_oracle.bad_plpgsql() RETURNS integer
LANGUAGE plpgsql AS 'BEGIN RETURN ); END';
RESET check_function_bodies;
