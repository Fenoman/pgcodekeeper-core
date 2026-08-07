CREATE TABLE public.t1 (
    id integer,
    status text,
    amount numeric,
    is_draft boolean
);

CREATE POLICY test_policy ON public.t1
  TO test_user
  USING ( t1.id > 0 AND t1.status <> 'hidden' )
  WITH CHECK ( t1.amount > 0 OR t1.is_draft );
