
SET search_path = pg_catalog;

ALTER OPERATOR public.+(integer, integer)
	SET (COMMUTATOR = +, NEGATOR = +, HASHES, MERGES);