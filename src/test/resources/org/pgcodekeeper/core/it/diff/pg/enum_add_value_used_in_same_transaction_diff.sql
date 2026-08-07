-- PostgreSQL refuses to use an enum value in the transaction that added it,
-- so this statement runs ahead of the transaction below and is not rolled back with it.

ALTER TYPE public.status
	ADD VALUE 'c' AFTER 'b';

START TRANSACTION;

SET search_path = pg_catalog;

ALTER TABLE ONLY public.t
	ALTER COLUMN st SET DEFAULT 'c';

COMMIT TRANSACTION;
