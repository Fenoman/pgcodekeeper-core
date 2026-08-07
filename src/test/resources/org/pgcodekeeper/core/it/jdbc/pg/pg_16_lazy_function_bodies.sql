CREATE TABLE public.lazy_dep(id integer PRIMARY KEY);

CREATE FUNCTION public.lazy_sql(integer) RETURNS integer
LANGUAGE sql AS 'SELECT id FROM public.lazy_dep WHERE id = $1';

CREATE FUNCTION public.lazy_plpgsql(integer) RETURNS integer
LANGUAGE plpgsql AS 'BEGIN RETURN (SELECT id FROM public.lazy_dep WHERE id = $1); END';

CREATE FUNCTION public.lazy_atomic(integer) RETURNS integer
LANGUAGE sql
BEGIN ATOMIC
 SELECT lazy_dep.id
    FROM public.lazy_dep
   WHERE (lazy_dep.id = $1);
END;

CREATE FUNCTION public.lazy_internal(integer) RETURNS integer
LANGUAGE internal AS 'int4abs';

SET check_function_bodies = off;
CREATE FUNCTION public.lazy_bad_sql() RETURNS integer
LANGUAGE sql AS 'SELECT )';
CREATE FUNCTION public.lazy_bad_plpgsql() RETURNS integer
LANGUAGE plpgsql AS 'BEGIN RETURN ); END';
RESET check_function_bodies;
