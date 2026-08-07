SET search_path = pg_catalog;

CREATE TABLE public.h (
    id integer
)
PARTITION BY HASH (id);

-- the shape the PostgreSQL manual writes, and a hand-written project with it
CREATE TABLE public.h0 PARTITION OF public.h
FOR VALUES WITH (MODULUS 4, REMAINDER 0);

CREATE TABLE public.l (
    c text
)
PARTITION BY LIST (c);

CREATE TABLE public.l0 PARTITION OF public.l
for values in ('a','b');

CREATE TABLE public.r (
    id integer
)
PARTITION BY RANGE (id);

CREATE TABLE public.r0 PARTITION OF public.r
FOR VALUES FROM (minvalue) TO (10);
