CREATE TABLE public.lib_table (
    id integer NOT NULL,
    value text
);

ALTER TABLE public.lib_table OWNER TO main_user;

GRANT SELECT ON TABLE public.lib_table TO main_role;

GRANT INSERT ON TABLE public.lib_table TO main_role;
