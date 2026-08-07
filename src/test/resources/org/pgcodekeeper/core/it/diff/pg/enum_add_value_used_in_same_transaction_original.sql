CREATE TYPE public.status AS ENUM (
    'a',
    'b'
);

CREATE TABLE public.t (
    st public.status
);
