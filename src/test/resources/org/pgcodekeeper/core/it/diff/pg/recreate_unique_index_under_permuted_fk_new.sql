CREATE TABLE public.p (
    a integer NOT NULL,
    b integer NOT NULL,
    c integer
);

CREATE UNIQUE INDEX p_ab_idx ON public.p USING btree (a, b);

CREATE TABLE public.child (
    x integer,
    y integer
);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x, y) REFERENCES public.p(b, a);
