-- add owned by

CREATE SEQUENCE public.t1_c1_seq
	AS integer
	START WITH 2
	INCREMENT BY 1
	NO MAXVALUE
	NO MINVALUE
	CACHE 1;

ALTER SEQUENCE public.t1_c1_seq OWNER TO owner;

CREATE TABLE public.t1 (
	c1 integer DEFAULT nextval('public.t1_c1_seq'::regclass) NOT NULL,
	c2 text
);

ALTER TABLE public.t1 OWNER TO owner;

ALTER SEQUENCE public.t1_c1_seq
	OWNED BY public.t1.c1;

-- drop owned by

CREATE SEQUENCE public.t2_c1_seq
	AS integer
	START WITH 1
	INCREMENT BY 1
	NO MAXVALUE
	NO MINVALUE
	CACHE 1;

CREATE TABLE public.t2 (
	c1 integer DEFAULT nextval('public.t2_c1_seq'::regclass) NOT NULL,
	c2 text
);
