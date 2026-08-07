CREATE VIEW public.v_type_aliases AS
SELECT r.f_division
FROM jsonb_to_record('{"f_division": 1}'::jsonb)
    AS r(f_division integer);
