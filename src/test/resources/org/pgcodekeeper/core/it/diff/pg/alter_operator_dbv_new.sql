CREATE OPERATOR public.+ (
    LEFTARG = integer,
    RIGHTARG = integer,
    COMMUTATOR = +,
    NEGATOR = <>,
    HASHES,
    MERGES
);