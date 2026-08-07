CREATE TABLE public.payments (
    id bigint NOT NULL,
    amount numeric,
    CONSTRAINT payments_amount_check CHECK ((amount is not null and amount > (0)::numeric))
);
