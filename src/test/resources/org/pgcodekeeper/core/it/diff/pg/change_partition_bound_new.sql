SET search_path = pg_catalog;

CREATE TABLE public.h (
    id integer
)
PARTITION BY HASH (id);

CREATE TABLE public.h0 PARTITION OF public.h
FOR VALUES WITH (MODULUS 8, REMAINDER 0);

CREATE TABLE public.l (
    c text
)
PARTITION BY LIST (c);

CREATE TABLE public.l0 PARTITION OF public.l
FOR VALUES IN ('a', 'B');
