CREATE TABLE public.people (
    id bigint NOT NULL,
    first_name text,
    last_name text
);

CREATE INDEX people_lower_idx ON public.people USING btree (
    CASE
        WHEN last_name is not NULL AND first_name is not NULL
        THEN coalesce(last_name, '') || ' ' || coalesce(first_name, '')
        ELSE last_name
    END
);
