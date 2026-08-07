CREATE TABLE public.orders (
    id integer NOT NULL,
    status text
);

CREATE RULE r_skip_locked AS
	ON UPDATE TO public.orders
	WHERE ( old.id > 0 AND old.status is NOT NULL )
	DO INSTEAD NOTHING;
