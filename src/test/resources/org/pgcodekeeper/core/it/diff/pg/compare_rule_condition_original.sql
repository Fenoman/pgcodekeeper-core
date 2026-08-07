CREATE TABLE public.orders (
    id integer NOT NULL,
    status text
);

CREATE RULE r_skip_locked AS
	ON UPDATE TO public.orders
	WHERE (old.id>0 and old.status is not null)
	DO INSTEAD NOTHING;
