CREATE TYPE public.status AS ENUM (
    'a',
    'b',
    'c'
);

CREATE TABLE public.t (
    st public.status DEFAULT 'c'
);
