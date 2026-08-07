CREATE TABLE public.tbl_drop (
	c1 integer,
	CONSTRAINT tbl_drop_chk CHECK ((c1 > 0))
);

CREATE TABLE public.tbl_nn (
	c1 integer CONSTRAINT tbl_nn_c1_nn NOT NULL
);

CREATE TABLE public.tbl_validate (
	c1 integer
);

ALTER TABLE public.tbl_validate
	ADD CONSTRAINT tbl_validate_chk CHECK ((c1 > 0)) NOT VALID;

CREATE TABLE public.tbl_alter (
	id integer,
	pid integer
);

ALTER TABLE public.tbl_alter
	ADD CONSTRAINT tbl_alter_pkey PRIMARY KEY (id);

ALTER TABLE public.tbl_alter
	ADD CONSTRAINT tbl_alter_fk FOREIGN KEY (pid) REFERENCES public.tbl_alter(id);
