CREATE SCHEMA test;

CREATE OPERATOR public.+ (
    LEFTARG = integer,
    RIGHTARG = integer,
    MERGES
);

CREATE OPERATOR public.- (
    LEFTARG = integer,
    RIGHTARG = integer,
    HASHES
);

CREATE OPERATOR test.+ (
    LEFTARG = integer,
    RIGHTARG = integer,
    COMMUTATOR = +
);

CREATE OPERATOR test.- (
    LEFTARG = integer,
    RIGHTARG = integer,
    NEGATOR = <>
);