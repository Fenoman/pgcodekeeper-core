CREATE TABLE public.orders (
    id integer NOT NULL,
    price numeric,
    qty integer,
    amount numeric DEFAULT CAST(0 AS  numeric),
    total numeric GENERATED ALWAYS AS (CAST( price * qty AS numeric )) STORED
);
