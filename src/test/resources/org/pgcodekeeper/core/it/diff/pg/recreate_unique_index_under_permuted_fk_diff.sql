SET search_path = pg_catalog;

-- DEPCY: This CONSTRAINT child_fk depends on the INDEX: public.p_ab_idx

ALTER TABLE public.child
	DROP CONSTRAINT child_fk;

DROP INDEX public.p_ab_idx;

CREATE UNIQUE INDEX p_ab_idx ON public.p USING btree (a, b);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x, y) REFERENCES public.p(b, a);
