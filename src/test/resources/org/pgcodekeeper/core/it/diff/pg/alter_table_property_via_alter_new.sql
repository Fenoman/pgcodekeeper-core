CREATE SERVER srv FOREIGN DATA WRAPPER postgres_fdw;

CREATE TABLE public.tbl_parent (
	c1 integer
);

CREATE TABLE public.tbl_logged (
	c1 integer
);

ALTER TABLE public.tbl_logged SET UNLOGGED;

CREATE TABLE public.tbl_space (
	c1 integer
);

ALTER TABLE public.tbl_space SET TABLESPACE ts;

CREATE TABLE public.tbl_opt (
	c1 integer
);

ALTER TABLE public.tbl_opt SET (fillfactor=70);

CREATE TABLE public.tbl_reset (
	c1 integer
)
WITH (fillfactor=70);

ALTER TABLE public.tbl_reset RESET (fillfactor);

CREATE TABLE public.tbl_cluster (
	c1 integer
);

CREATE INDEX tbl_cluster_idx ON public.tbl_cluster (c1);

ALTER TABLE public.tbl_cluster CLUSTER ON tbl_cluster_idx;

ALTER TABLE public.tbl_cluster SET WITHOUT CLUSTER;

CREATE TABLE public.tbl_inherit (
	c1 integer
);

ALTER TABLE public.tbl_inherit INHERIT public.tbl_parent;

CREATE TABLE public.tbl_detach (
	c1 integer
)
INHERITS (public.tbl_parent);

ALTER TABLE public.tbl_detach NO INHERIT public.tbl_parent;

CREATE FOREIGN TABLE public.tbl_foreign (
	c1 integer
)
SERVER srv
OPTIONS (
    schema_name 'public',
    updatable 'false'
);

ALTER FOREIGN TABLE public.tbl_foreign OPTIONS (SET schema_name 'other', ADD table_name 'remote', DROP updatable);
