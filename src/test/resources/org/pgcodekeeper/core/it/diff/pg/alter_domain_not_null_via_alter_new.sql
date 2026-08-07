CREATE DOMAIN public.dom_set AS integer;

ALTER DOMAIN public.dom_set SET NOT NULL;

CREATE DOMAIN public.dom_drop AS integer NOT NULL;

ALTER DOMAIN public.dom_drop DROP NOT NULL;
