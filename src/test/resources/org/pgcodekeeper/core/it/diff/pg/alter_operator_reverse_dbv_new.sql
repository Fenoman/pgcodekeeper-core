CREATE SCHEMA test;

CREATE OPERATOR public.+ (
    LEFTARG = integer,
    RIGHTARG = integer
);

CREATE OPERATOR public.- (
    LEFTARG = integer,
    RIGHTARG = integer
);

CREATE OPERATOR test.+ (
    LEFTARG = integer,
    RIGHTARG = integer
);

CREATE OPERATOR test.- (
    LEFTARG = integer,
    RIGHTARG = integer
);
