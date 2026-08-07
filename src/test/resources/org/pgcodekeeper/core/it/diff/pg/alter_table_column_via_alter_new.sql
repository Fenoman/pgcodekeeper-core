CREATE TABLE public.tbl_type (
	c1 integer
);

ALTER TABLE public.tbl_type ALTER COLUMN c1 TYPE bigint;

CREATE TABLE public.tbl_drop_col (
	c1 integer,
	c2 integer
);

ALTER TABLE public.tbl_drop_col DROP COLUMN c2;

CREATE TABLE public.tbl_def (
	c1 integer DEFAULT 1
);

ALTER TABLE public.tbl_def ALTER COLUMN c1 DROP DEFAULT;

CREATE TABLE public.tbl_nn (
	c1 integer NOT NULL
);

ALTER TABLE public.tbl_nn ALTER COLUMN c1 DROP NOT NULL;

CREATE TABLE public.tbl_rename (
	c1 integer,
	c2 integer
);

ALTER TABLE public.tbl_rename RENAME COLUMN c2 TO c3;
