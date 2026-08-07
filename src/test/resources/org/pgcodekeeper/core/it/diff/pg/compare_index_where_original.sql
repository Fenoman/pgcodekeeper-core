CREATE TABLE public.orders (
    id bigint NOT NULL,
    status text,
    total numeric
);

CREATE INDEX orders_open_idx ON public.orders USING btree (id) WHERE (status = 'open'::text);
