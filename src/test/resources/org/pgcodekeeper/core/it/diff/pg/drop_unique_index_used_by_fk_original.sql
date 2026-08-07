CREATE TABLE public.p (
    a integer NOT NULL
);

CREATE UNIQUE INDEX p_a_idx ON public.p USING btree (a);

CREATE TABLE public.child (
    x integer
);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x) REFERENCES public.p(a);
