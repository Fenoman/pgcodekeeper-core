CREATE SCHEMA pgck_sequence_survival;

ALTER SCHEMA pgck_sequence_survival OWNER TO test;

CREATE TABLE pgck_sequence_survival.a (
    keep integer,
    move_id integer,
    detach_id integer
);

ALTER TABLE pgck_sequence_survival.a OWNER TO test;

CREATE TABLE pgck_sequence_survival.b (
    id integer
);

ALTER TABLE pgck_sequence_survival.b OWNER TO test;

CREATE SEQUENCE pgck_sequence_survival.move_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pgck_sequence_survival.move_seq OWNER TO test;

ALTER SEQUENCE pgck_sequence_survival.move_seq
    OWNED BY pgck_sequence_survival.a.move_id;

CREATE SEQUENCE pgck_sequence_survival.detach_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pgck_sequence_survival.detach_seq OWNER TO test;

ALTER SEQUENCE pgck_sequence_survival.detach_seq
    OWNED BY pgck_sequence_survival.a.detach_id;

CREATE TABLE pgck_sequence_survival.recreate_table (
    id integer,
    payload integer
);

ALTER TABLE pgck_sequence_survival.recreate_table OWNER TO test;

CREATE SEQUENCE pgck_sequence_survival.recreate_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pgck_sequence_survival.recreate_seq OWNER TO test;

ALTER SEQUENCE pgck_sequence_survival.recreate_seq
    OWNED BY pgck_sequence_survival.recreate_table.id;
