SET search_path = pg_catalog;

ALTER TABLE ONLY public.tbl_drop_col
	DROP COLUMN c2;

ALTER TABLE public.tbl_type
	ALTER COLUMN c1 TYPE bigint USING c1::bigint; /* TYPE change - table: public.tbl_type original: integer new: bigint */

ALTER TABLE ONLY public.tbl_def
	ALTER COLUMN c1 DROP DEFAULT;

ALTER TABLE ONLY public.tbl_nn
	ALTER COLUMN c1 DROP NOT NULL;
