CREATE TABLE public.events (
    id bigint NOT NULL,
    kind text,
    amount numeric
);

CREATE STATISTICS public.events_stat ON (case when amount > 0 then amount + 1 else amount - 1 end), kind FROM public.events;
