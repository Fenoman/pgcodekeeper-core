SET search_path = pg_catalog;

DROP OPERATOR public.+(integer, integer);

CREATE OPERATOR public.+ (
	PROCEDURE = null,
	LEFTARG = integer,
	RIGHTARG = integer,
	COMMUTATOR = +,
	NEGATOR = <>,
	MERGES,
	HASHES
);
