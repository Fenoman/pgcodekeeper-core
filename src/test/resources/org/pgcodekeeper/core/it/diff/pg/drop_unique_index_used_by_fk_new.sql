CREATE TABLE public.p (
    a integer NOT NULL
);

ALTER TABLE public.p
	ADD CONSTRAINT p_a_uc UNIQUE (a);

CREATE TABLE public.child (
    x integer
);

ALTER TABLE public.child
	ADD CONSTRAINT child_fk FOREIGN KEY (x) REFERENCES public.p(a);
