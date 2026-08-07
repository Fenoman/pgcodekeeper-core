SET search_path = pg_catalog;

ALTER DOMAIN public.dom_drop
	DROP CONSTRAINT dom_drop_check;

ALTER DOMAIN public.dom_validate
	VALIDATE CONSTRAINT dom_validate_check;
