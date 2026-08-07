CREATE TABLE public.child (
    child_value integer
) INHERITS (public.parent);

ALTER TABLE public.child
    ADD CONSTRAINT child_payload_nn CHECK (payload IS NOT NULL) NOT VALID;
