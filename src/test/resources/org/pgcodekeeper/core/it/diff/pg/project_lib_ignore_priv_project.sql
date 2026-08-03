CREATE SCHEMA public;

CREATE TABLE public.project_table (
    id integer NOT NULL
);

ALTER TABLE public.project_table OWNER TO main_user;

GRANT SELECT ON TABLE public.project_table TO main_role;