SET search_path = pg_catalog;

-- DEPCY: This CONSTRAINT child_fk depends on the INDEX: public.p_a_idx

ALTER TABLE public.child
	DROP CONSTRAINT child_fk;

DROP INDEX public.p_a_idx;

ALTER TABLE public.p
	ADD CONSTRAINT p_a_uc UNIQUE (a);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x) REFERENCES public.p(a);
