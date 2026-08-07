SET search_path = pg_catalog;

ALTER TABLE public.tbl_drop
	DROP CONSTRAINT tbl_drop_chk;

ALTER TABLE ONLY public.tbl_nn
	ALTER COLUMN c1 DROP NOT NULL;

ALTER TABLE public.tbl_validate
	VALIDATE CONSTRAINT tbl_validate_chk;

ALTER TABLE public.tbl_alter
	ALTER CONSTRAINT tbl_alter_fk DEFERRABLE INITIALLY DEFERRED;
