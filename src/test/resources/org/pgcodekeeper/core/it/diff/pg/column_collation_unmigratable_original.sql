-- The source of the migration names a collation that its target leaves
-- unspecified. Such a collation cannot be migrated: pgCodeKeeper never emits a
-- collation reset. The table therefore carries no change of its own, while its
-- index and the second table do change, so the tree here is not empty.

CREATE TABLE public.unmigratable_collation (
	c_id integer,
	c_period text COLLATE pg_catalog."ru_RU"
);

CREATE INDEX unmigratable_collation_idx ON public.unmigratable_collation USING btree (c_id);

-- the collation of a generated column is migratable, by recreating the column
CREATE TABLE public.generated_collation (
	c_source text,
	c_period text COLLATE pg_catalog."ru_RU" GENERATED ALWAYS AS (c_source) STORED
);

-- both sides name a collation: a plain difference, altered as always
CREATE TABLE public.named_collation (
	c_period text COLLATE pg_catalog."ru_RU"
);

CREATE TABLE public.plain_change (
	c_id integer
);
