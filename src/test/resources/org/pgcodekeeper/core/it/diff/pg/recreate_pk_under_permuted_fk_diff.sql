SET search_path = pg_catalog;

-- DEPCY: This CONSTRAINT child_fk depends on the CONSTRAINT: public.p.p_pk

ALTER TABLE public.child
	DROP CONSTRAINT child_fk;

ALTER TABLE public.p
	DROP CONSTRAINT p_pk;

ALTER TABLE public.p
	ADD CONSTRAINT p_pk PRIMARY KEY (a, b);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x, y) REFERENCES public.p(b, a);
