SET search_path = pg_catalog;

CREATE TABLE public.h (
    id integer
)
PARTITION BY HASH (id);

-- the shape pg_get_expr writes: modulus and remainder in lower case
CREATE TABLE public.h0 PARTITION OF public.h
FOR VALUES WITH (modulus 4, remainder 0);

CREATE TABLE public.l (
    c text
)
PARTITION BY LIST (c);

-- the shape pg_get_expr writes: a space after the comma
CREATE TABLE public.l0 PARTITION OF public.l
FOR VALUES IN ('a', 'b');

CREATE TABLE public.r (
    id integer
)
PARTITION BY RANGE (id);

CREATE TABLE public.r0 PARTITION OF public.r
FOR VALUES FROM (MINVALUE) TO (10);
