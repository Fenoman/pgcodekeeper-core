CREATE TABLE public.orders (
    id integer NOT NULL,
    price numeric,
    qty integer,
    amount numeric DEFAULT cast(0 as numeric),
    total numeric GENERATED ALWAYS AS (cast(price*qty as numeric)) STORED
);
