CREATE DOMAIN public.dom_drop AS integer
	CONSTRAINT dom_drop_check CHECK ((VALUE > 0));

ALTER DOMAIN public.dom_drop DROP CONSTRAINT dom_drop_check;

CREATE DOMAIN public.dom_validate AS integer;

ALTER DOMAIN public.dom_validate
	ADD CONSTRAINT dom_validate_check CHECK ((VALUE > 0)) NOT VALID;

ALTER DOMAIN public.dom_validate VALIDATE CONSTRAINT dom_validate_check;
