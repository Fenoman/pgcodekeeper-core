CREATE MATERIALIZED VIEW public.test_mv
USING columnar
AS SELECT 1 AS id, 'test' AS name
WITH DATA;