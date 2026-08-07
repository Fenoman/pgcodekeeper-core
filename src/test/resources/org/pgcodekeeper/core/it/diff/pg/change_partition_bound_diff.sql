SET search_path = pg_catalog;

ALTER TABLE public.h
	DETACH PARTITION public.h0;

ALTER TABLE public.h
	ATTACH PARTITION public.h0 FOR VALUES WITH (MODULUS 8, REMAINDER 0);

ALTER TABLE public.l
	DETACH PARTITION public.l0;

ALTER TABLE public.l
	ATTACH PARTITION public.l0 FOR VALUES IN ('a', 'B');
