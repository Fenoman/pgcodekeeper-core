CREATE TABLE public.events (
    id bigint NOT NULL,
    kind text,
    amount numeric
);

CREATE STATISTICS public.events_stat ON (
        CASE
            WHEN amount > 0
            THEN amount + 1
            ELSE amount - 1
        END
    ), kind
    FROM public.events;
