SCHEMA|routine_oracle
PARENT|DATABASE|<database>
CHILDREN|FUNCTION|routine_oracle.ordered(integer),FUNCTION|routine_oracle.plpgsql_config(integer),PROCEDURE|routine_oracle.process_one(integer),FUNCTION|routine_oracle.aggregate_state(integer, integer),AGGREGATE|routine_oracle.aggregate_sum(integer),FUNCTION|routine_oracle.atomic_lookup(integer),FUNCTION|routine_oracle.internal_abs(integer),FUNCTION|routine_oracle.overloaded(integer),FUNCTION|routine_oracle.overloaded(text),FUNCTION|routine_oracle.utf8_body(),FUNCTION|routine_oracle.bad_sql(),FUNCTION|routine_oracle.bad_plpgsql(),TABLE|routine_oracle.dep
CREATE SCHEMA routine_oracle;
-- MODEL OBJECT --
SCHEMA|"Routine Oracle"
PARENT|DATABASE|<database>
CHILDREN|FUNCTION|"Routine Oracle"."Mixed Routine"(integer)
CREATE SCHEMA "Routine Oracle";
-- MODEL OBJECT --
FUNCTION|routine_oracle.ordered(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.ordered(p_id integer = 41) RETURNS integer
    LANGUAGE sql
    SET search_path TO 'routine_oracle', 'pg_catalog'
    AS $$
SELECT dep.id + p_id
FROM routine_oracle.dep dep
WHERE dep.id = p_id
$$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.plpgsql_config(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.plpgsql_config(p_id integer) RETURNS integer
    LANGUAGE plpgsql
    SET search_path TO 'routine_oracle', 'pg_catalog'
    AS $$
BEGIN
    RETURN (SELECT dep.id FROM routine_oracle.dep dep WHERE dep.id = p_id);
END
$$;
-- MODEL OBJECT --
PROCEDURE|routine_oracle.process_one(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE PROCEDURE routine_oracle.process_one(p_id integer)
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM dep.id FROM routine_oracle.dep dep WHERE dep.id = p_id;
END
$$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.aggregate_state(integer, integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.aggregate_state(state integer, value integer) RETURNS integer
    LANGUAGE sql IMMUTABLE
    AS $$SELECT COALESCE(state, 0) + value$$;
-- MODEL OBJECT --
AGGREGATE|routine_oracle.aggregate_sum(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE AGGREGATE routine_oracle.aggregate_sum(integer) (
	SFUNC = routine_oracle.aggregate_state,
	STYPE = integer,
	INITCOND = '0'
);
-- MODEL OBJECT --
FUNCTION|routine_oracle.atomic_lookup(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.atomic_lookup(p_id integer) RETURNS integer
    LANGUAGE sql
    BEGIN ATOMIC
    SELECT dep.id
    FROM routine_oracle.dep dep
    WHERE dep.id = p_id;
END;
-- MODEL OBJECT --
FUNCTION|routine_oracle.internal_abs(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.internal_abs(integer) RETURNS integer
    LANGUAGE internal STRICT
    AS $$int4abs$$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.overloaded(integer)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.overloaded(integer) RETURNS integer
    LANGUAGE sql
    AS $_$SELECT $1 + 1$_$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.overloaded(text)
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.overloaded(text) RETURNS integer
    LANGUAGE sql
    AS $_$SELECT length($1)$_$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.utf8_body()
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.utf8_body() RETURNS text
    LANGUAGE sql
    AS $_$
SELECT 'Привет 😀 $function$'
    || E'\nвторая строка'
$_$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.bad_sql()
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.bad_sql() RETURNS integer
    LANGUAGE sql
    AS $$SELECT )$$;
-- MODEL OBJECT --
FUNCTION|routine_oracle.bad_plpgsql()
PARENT|SCHEMA|routine_oracle
CHILDREN|
CREATE OR REPLACE FUNCTION routine_oracle.bad_plpgsql() RETURNS integer
    LANGUAGE plpgsql
    AS $$BEGIN RETURN ); END$$;
-- MODEL OBJECT --
TABLE|routine_oracle.dep
PARENT|SCHEMA|routine_oracle
CHILDREN|COLUMN|routine_oracle.dep.id
CREATE TABLE routine_oracle.dep (
	id integer
);
-- MODEL OBJECT --
COLUMN|routine_oracle.dep.id
PARENT|TABLE|routine_oracle.dep
CHILDREN|
ALTER TABLE routine_oracle.dep
	ADD COLUMN id integer;
-- MODEL OBJECT --
FUNCTION|"Routine Oracle"."Mixed Routine"(integer)
PARENT|SCHEMA|"Routine Oracle"
CHILDREN|
CREATE OR REPLACE FUNCTION "Routine Oracle"."Mixed Routine"(integer) RETURNS integer
    LANGUAGE sql
    AS $_$SELECT $1 + 2$_$;
