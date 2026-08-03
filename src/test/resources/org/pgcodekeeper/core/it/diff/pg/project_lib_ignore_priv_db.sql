CREATE TABLE public.project_table (
    id integer NOT NULL
);

ALTER TABLE public.project_table OWNER TO main_user;

GRANT SELECT ON TABLE public.project_table TO main_role;

CREATE TABLE public.lib_table (
    id integer NOT NULL,
    value text
);

ALTER TABLE public.lib_table OWNER TO other_user;

GRANT SELECT ON TABLE public.lib_table TO other_role;

GRANT INSERT ON TABLE public.lib_table TO other_role;
