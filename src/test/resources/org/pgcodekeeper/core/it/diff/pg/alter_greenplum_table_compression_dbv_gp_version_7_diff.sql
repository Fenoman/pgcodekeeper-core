SET search_path = pg_catalog;

ALTER TABLE public.sales1 SET (compresstype=zstd, compresslevel=4, blocksize=65536);

DROP TABLE public.sales3;

DROP TABLE public.sales4;

ALTER TABLE public.sales5 SET (compresstype=zstd, compresslevel=4);

ALTER TABLE public.sales6 SET (fillfactor=50);

ALTER TABLE public.sales6 SET ACCESS METHOD my_method;

ALTER TABLE public.sales2
	ALTER COLUMN col1 SET ENCODING (COMPRESSTYPE = zstd, COMPRESSLEVEL = 4, BLOCKSIZE = 65536);

ALTER TABLE public.sales5
	ALTER COLUMN col2 SET ENCODING (COMPRESSTYPE = zstd, COMPRESSLEVEL = 4, BLOCKSIZE = 32768);

CREATE TABLE public.sales3 (
	id integer,
	col1 text
)
USING ao_row
WITH (compresstype=zstd, compresslevel=4)
DISTRIBUTED BY (id);

CREATE TABLE public.sales4 (
	id integer ENCODING (COMPRESSTYPE = zstd, COMPRESSLEVEL = 4, BLOCKSIZE = 32768),
	col1 text ENCODING (COMPRESSTYPE = rle_type, COMPRESSLEVEL = 1, BLOCKSIZE = 32768)
)
USING ao_column
WITH (compresstype=zstd, compresslevel=4)
DISTRIBUTED BY (id);