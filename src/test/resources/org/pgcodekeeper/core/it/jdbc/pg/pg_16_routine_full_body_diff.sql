SET search_path = pg_catalog;

DROP FUNCTION routine_oracle.ordered(p_id integer);

CREATE OR REPLACE FUNCTION routine_oracle.atomic_lookup(p_id integer) RETURNS integer
    LANGUAGE sql
    BEGIN ATOMIC
    SELECT dep.id
    FROM routine_oracle.dep dep
    WHERE dep.id = p_id;
END;

CREATE OR REPLACE FUNCTION routine_oracle.ordered(p_id integer = 42) RETURNS integer
    LANGUAGE sql
    SET search_path TO 'pg_catalog', 'routine_oracle'
    AS $$
SELECT dep.id + p_id + 1
FROM routine_oracle.dep dep
WHERE dep.id = p_id
$$;
