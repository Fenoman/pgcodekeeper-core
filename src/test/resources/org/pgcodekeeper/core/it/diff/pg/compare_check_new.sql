CREATE TABLE public.payments (
    id bigint NOT NULL,
    amount numeric,
    CONSTRAINT payments_amount_check check ( ( amount is NOT NULL AND amount > (0)::numeric ) )
);
