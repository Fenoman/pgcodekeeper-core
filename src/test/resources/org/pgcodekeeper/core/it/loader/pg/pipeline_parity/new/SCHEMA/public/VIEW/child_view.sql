CREATE VIEW public.child_view AS
SELECT c.id, c.payload, c.child_value
FROM public.child c;
