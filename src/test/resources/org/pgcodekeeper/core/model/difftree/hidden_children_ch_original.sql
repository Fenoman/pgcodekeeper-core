CREATE TABLE default.only_hidden
(
	`col1` Int64 NOT NULL,
	`col2` Int64 NOT NULL
)
ENGINE = MergeTree
ORDER BY col1
SETTINGS index_granularity = 8192;

ALTER TABLE default.only_hidden ADD INDEX t_hidden col1 > 0 TYPE bloom_filter GRANULARITY 1;
