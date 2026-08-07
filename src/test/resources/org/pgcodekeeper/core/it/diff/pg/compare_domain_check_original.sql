CREATE DOMAIN public.positive_amount AS numeric
    CONSTRAINT positive_amount_check CHECK ((VALUE is not null and VALUE > (0)::numeric));
