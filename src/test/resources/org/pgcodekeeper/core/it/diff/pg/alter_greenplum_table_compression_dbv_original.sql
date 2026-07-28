CREATE TABLE public.sales1 (
	id integer,
	col1 text
)
USING ao_row
WITH (compresstype=zlib, compresslevel=5)
DISTRIBUTED BY (id);

CREATE TABLE public.sales2 (
	id integer ENCODING (COMPRESSTYPE = zlib, COMPRESSLEVEL = 1, BLOCKSIZE = 32768),
	col1 text ENCODING (COMPRESSTYPE = none, COMPRESSLEVEL = 0, BLOCKSIZE = 32768)
)
USING ao_column
DISTRIBUTED BY (id);

CREATE TABLE public.sales3 (
	id integer,
	col1 text
)
DISTRIBUTED BY (id);

CREATE TABLE public.sales4 (
	id integer,
	col1 text
)
USING ao_row
WITH (compresstype=zlib, compresslevel=5)
DISTRIBUTED BY (id);

CREATE TABLE public.sales5 (
	id integer,
	col1 text ENCODING (COMPRESSTYPE = rle_type, COMPRESSLEVEL = 1, BLOCKSIZE = 32768),
	col2 text ENCODING (COMPRESSTYPE = zlib, COMPRESSLEVEL = 5, BLOCKSIZE = 32768)
)
USING ao_column
WITH (compresstype=zlib, compresslevel=5)
DISTRIBUTED BY (id);

CREATE TABLE public.sales6 (
	id integer,
	col1 text
)
WITH (fillfactor=70)
DISTRIBUTED BY (id);