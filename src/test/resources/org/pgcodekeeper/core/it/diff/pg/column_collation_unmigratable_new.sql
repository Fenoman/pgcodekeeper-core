-- See column_collation_unmigratable_original.sql

CREATE TABLE public.unmigratable_collation (
	c_id integer,
	c_period text
);

CREATE INDEX unmigratable_collation_idx ON public.unmigratable_collation USING btree (c_period);

-- the collation of a generated column is migratable, by recreating the column
CREATE TABLE public.generated_collation (
	c_source text,
	c_period text GENERATED ALWAYS AS (c_source) STORED
);

-- both sides name a collation: a plain difference, altered as always
CREATE TABLE public.named_collation (
	c_period text COLLATE pg_catalog."sv_SE"
);

CREATE TABLE public.plain_change (
	c_id bigint
);
