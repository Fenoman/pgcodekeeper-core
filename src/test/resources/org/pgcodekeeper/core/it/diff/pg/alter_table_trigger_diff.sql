SET search_path = pg_catalog;

CREATE TRIGGER trigtest_b_stmt_tg
	BEFORE INSERT OR UPDATE OR DELETE ON public.trigtest
	FOR EACH ROW
	EXECUTE PROCEDURE public.trigtest();

CREATE TRIGGER trigtest_c_stmt_tg
	BEFORE INSERT OR UPDATE OR DELETE ON public.trigtest
	FOR EACH ROW
	EXECUTE PROCEDURE public.trigtest();