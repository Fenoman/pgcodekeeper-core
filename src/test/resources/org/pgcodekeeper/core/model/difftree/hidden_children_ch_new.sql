CREATE TABLE default.only_hidden
(
	`col1` Int64 NOT NULL,
	`col2` Int64 NOT NULL
)
ENGINE = MergeTree
ORDER BY col1
SETTINGS index_granularity = 8192;
