SET search_path = pg_catalog;

DROP OPERATOR public.+(integer, integer);

DROP OPERATOR public.-(integer, integer);

DROP OPERATOR test.+(integer, integer);

DROP OPERATOR test.-(integer, integer);

CREATE OPERATOR public.+ (
	PROCEDURE = null,
	LEFTARG = integer,
	RIGHTARG = integer
);

CREATE OPERATOR public.- (
	PROCEDURE = null,
	LEFTARG = integer,
	RIGHTARG = integer
);

CREATE OPERATOR test.+ (
	PROCEDURE = null,
	LEFTARG = integer,
	RIGHTARG = integer
);

CREATE OPERATOR test.- (
	PROCEDURE = null,
	LEFTARG = integer,
	RIGHTARG = integer
);
