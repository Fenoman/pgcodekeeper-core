CREATE TABLE public.sales1 (
	id integer,
	col1 text
)
USING ao_row
WITH (compresstype=zstd, compresslevel=4, blocksize=65536)
DISTRIBUTED BY (id);

CREATE TABLE public.sales2 (
	id integer ENCODING (COMPRESSTYPE = zlib, COMPRESSLEVEL = 1, BLOCKSIZE = 32768),
	col1 text ENCODING (COMPRESSTYPE = zstd, COMPRESSLEVEL = 4, BLOCKSIZE = 65536)
)
USING ao_column
DISTRIBUTED BY (id);

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

CREATE TABLE public.sales5 (
	id integer,
	col1 text ENCODING (COMPRESSTYPE = rle_type, COMPRESSLEVEL = 1, BLOCKSIZE = 32768),
	col2 text ENCODING (COMPRESSTYPE = zstd, COMPRESSLEVEL = 4, BLOCKSIZE = 32768)
)
USING ao_column
WITH (compresstype=zstd, compresslevel=4)
DISTRIBUTED BY (id);

CREATE TABLE public.sales6 (
	id integer,
	col1 text
)
USING my_method
WITH (fillfactor=50)
DISTRIBUTED BY (id);