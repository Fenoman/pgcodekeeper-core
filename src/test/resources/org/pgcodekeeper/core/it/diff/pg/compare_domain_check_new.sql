CREATE DOMAIN public.positive_amount AS numeric;

ALTER DOMAIN public.positive_amount
    ADD CONSTRAINT positive_amount_check check ( ( VALUE is NOT NULL AND VALUE > (0)::numeric ) );
