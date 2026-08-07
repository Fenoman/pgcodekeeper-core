CREATE DOMAIN public.dom_drop AS integer
	CONSTRAINT dom_drop_check CHECK ((VALUE > 0));

CREATE DOMAIN public.dom_validate AS integer;

ALTER DOMAIN public.dom_validate
	ADD CONSTRAINT dom_validate_check CHECK ((VALUE > 0)) NOT VALID;
