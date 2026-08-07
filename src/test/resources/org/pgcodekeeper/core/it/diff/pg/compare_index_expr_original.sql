CREATE TABLE public.people (
    id bigint NOT NULL,
    first_name text,
    last_name text
);

CREATE INDEX people_lower_idx ON public.people USING btree (case when last_name is not null and first_name is not null then coalesce(last_name, '') || ' ' || coalesce(first_name, '') else last_name end);
