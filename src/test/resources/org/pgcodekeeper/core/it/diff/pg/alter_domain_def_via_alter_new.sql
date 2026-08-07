CREATE DOMAIN public.dom_set AS integer;

ALTER DOMAIN public.dom_set SET DEFAULT ( -100 );

CREATE DOMAIN public.dom_drop AS integer DEFAULT (-100);

ALTER DOMAIN public.dom_drop DROP DEFAULT;
