SET search_path = pg_catalog;

ALTER TABLE public.tbl_logged
	SET UNLOGGED;

ALTER TABLE public.tbl_space
	SET TABLESPACE ts;

ALTER TABLE public.tbl_opt SET (fillfactor='70');

ALTER TABLE public.tbl_reset RESET (fillfactor);

ALTER TABLE public.tbl_inherit
	INHERIT public.tbl_parent;

ALTER TABLE public.tbl_detach
	NO INHERIT public.tbl_parent;

ALTER FOREIGN TABLE public.tbl_foreign OPTIONS (SET schema_name 'other');

ALTER FOREIGN TABLE public.tbl_foreign OPTIONS (DROP updatable );

ALTER FOREIGN TABLE public.tbl_foreign OPTIONS (ADD table_name 'remote');

ALTER TABLE public.tbl_cluster SET WITHOUT CLUSTER;