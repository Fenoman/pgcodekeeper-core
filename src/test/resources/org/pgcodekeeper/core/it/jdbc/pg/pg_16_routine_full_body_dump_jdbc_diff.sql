SET search_path = pg_catalog;

CREATE OR REPLACE FUNCTION routine_oracle.atomic_lookup(p_id integer) RETURNS integer
    LANGUAGE sql
    BEGIN ATOMIC
 SELECT dep.id
    FROM routine_oracle.dep dep
   WHERE (dep.id = atomic_lookup.p_id);
END;
