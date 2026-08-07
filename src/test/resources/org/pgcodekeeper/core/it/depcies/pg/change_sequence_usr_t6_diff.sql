SET search_path = pg_catalog;

ALTER SEQUENCE public.s6
	OWNED BY NONE;

DROP TABLE public.t6;

CREATE TABLE public.t6 (
	c2 text,
	c1 integer DEFAULT nextval('public.s6'::regclass) NOT NULL,
	c3 integer DEFAULT public.fff(1, 2)
);

ALTER TABLE public.t6 OWNER TO owner;

ALTER SEQUENCE public.s6
	INCREMENT BY 1;

ALTER SEQUENCE public.s6
	OWNED BY public.t6.c1;
