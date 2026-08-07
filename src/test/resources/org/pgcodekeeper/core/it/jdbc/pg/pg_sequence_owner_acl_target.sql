CREATE SCHEMA pgck_sequence_owner_acl;

ALTER SCHEMA pgck_sequence_owner_acl OWNER TO test;

CREATE TABLE pgck_sequence_owner_acl.owner_acl_table (
    id integer NOT NULL
);

ALTER TABLE pgck_sequence_owner_acl.owner_acl_table OWNER TO pgck_soa_new;

CREATE SEQUENCE pgck_sequence_owner_acl.owner_acl_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE pgck_sequence_owner_acl.owner_acl_seq OWNER TO pgck_soa_new;

ALTER SEQUENCE pgck_sequence_owner_acl.owner_acl_seq
    OWNED BY pgck_sequence_owner_acl.owner_acl_table.id;

GRANT USAGE ON SEQUENCE pgck_sequence_owner_acl.owner_acl_seq TO pgck_soa_reader;
