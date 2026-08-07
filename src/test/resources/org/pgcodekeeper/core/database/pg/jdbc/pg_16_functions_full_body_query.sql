WITH extension_deps AS (
  SELECT
    objid
  FROM pg_catalog.pg_depend
  WHERE deptype IN ('e', 'i')
    AND classid = 'pg_catalog.pg_proc'::pg_catalog.regclass
)
SELECT
  d.description,
  res.proname,
  res.prorettype::bigint,
  res.proallargtypes::bigint[],
  res.proargmodes,
  res.proargnames,
  res.proretset,
  array(select pg_catalog.unnest(res.proargtypes))::bigint[] as argtypes,
  l.lanname AS lang_name,
  res.prosrc,
  res.provolatile,
  res.proleakproof,
  res.proisstrict,
  res.prosecdef,
  res.procost::real,
  res.prorows::real,
  res.proconfig,
  res.probin,
  pg_catalog.pg_get_expr(res.proargdefaults, 0) AS default_values_as_string,
  res.pronargs,
  res.protrftypes::bigint[],
  res.proparallel,
  res.prosupport AS support_func,
  res.prokind = 'a' AS proisagg,
  res.prokind = 'w' AS proiswindow,
  res.prokind = 'p' AS proisproc,
  case when (res.prosrc is null or res.prosrc='') and l.lanname = 'sql'
    then pg_get_function_sqlbody(res.oid) end as prosqlbody,
  res.pronamespace
FROM pg_catalog.pg_proc res
LEFT JOIN pg_catalog.pg_description d ON d.objoid = res.oid AND d.classoid = 'pg_catalog.pg_proc'::pg_catalog.regclass<TRAILING_SPACE>
LEFT JOIN pg_catalog.pg_language l ON l.oid = res.prolang
WHERE res.oid NOT IN (SELECT objid FROM extension_deps)
  AND res.prokind <> 'a'
  AND res.pronamespace IN (11, 22)
ORDER BY res.oid
