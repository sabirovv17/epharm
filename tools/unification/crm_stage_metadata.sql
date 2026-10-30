-- Read-only source catalogue. Definitions are retained as inert provenance, never executed.
SELECT json_build_object(
  'database', (SELECT json_build_object(
    'name', current_database(), 'version', current_setting('server_version'),
    'versionNum', current_setting('server_version_num')::integer,
    'encoding', pg_encoding_to_char(d.encoding), 'collate', d.datcollate,
    'ctype', d.datctype, 'localeProvider', d.datlocprovider,
    'sizeBytes', pg_database_size(d.oid)) FROM pg_database d WHERE d.datname = current_database()),
  'schemas', (SELECT coalesce(json_agg(json_build_object('name', nspname, 'owner', pg_get_userbyid(nspowner),
    'acl', nspacl::text) ORDER BY nspname), '[]'::json) FROM pg_namespace WHERE nspname IN ('public', 'api')),
  'extensions', (SELECT coalesce(json_agg(json_build_object('name', e.extname, 'version', e.extversion,
    'schema', n.nspname) ORDER BY e.extname), '[]'::json)
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace),
  'enums', (SELECT coalesce(json_agg(json_build_object('name', t.typname,
    'labels', (SELECT json_agg(enumlabel ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid = t.oid))
    ORDER BY t.typname), '[]'::json)
    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = 'public' AND t.typtype = 'e'),
  'tables', (SELECT coalesce(json_agg(json_build_object(
    'name', r.relname, 'kind', r.relkind, 'owner', pg_get_userbyid(r.relowner), 'acl', r.relacl::text,
    'rowSecurity', r.relrowsecurity, 'forceRowSecurity', r.relforcerowsecurity,
    'comment', obj_description(r.oid, 'pg_class'),
    'columns', (SELECT json_agg(json_build_object(
      'name', a.attname, 'position', a.attnum, 'typeSchema', tn.nspname, 'typeName', t.typname,
      'typeModifier', a.atttypmod, 'notNull', a.attnotnull, 'identity', a.attidentity,
      'generated', a.attgenerated, 'default', pg_get_expr(ad.adbin, ad.adrelid),
      'collationSchema', cn.nspname, 'collationName', col.collname,
      'characterLength', ic.character_maximum_length, 'numericPrecision', ic.numeric_precision,
      'numericScale', ic.numeric_scale, 'acl', a.attacl::text, 'comment', col_description(r.oid, a.attnum))
      ORDER BY a.attnum)
      FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid JOIN pg_namespace tn ON tn.oid = t.typnamespace
      LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
      LEFT JOIN pg_collation col ON col.oid = a.attcollation LEFT JOIN pg_namespace cn ON cn.oid = col.collnamespace
      LEFT JOIN information_schema.columns ic ON ic.table_schema = 'public' AND ic.table_name = r.relname AND ic.column_name = a.attname
      WHERE a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped),
    'constraints', (SELECT coalesce(json_agg(json_build_object(
      'name', c.conname, 'kind', c.contype,
      'columns', (SELECT coalesce(json_agg(a.attname ORDER BY k.ordinality), '[]'::json)
        FROM unnest(c.conkey) WITH ORDINALITY k(num, ordinality) JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum = k.num),
      'foreignSchema', fn.nspname, 'foreignTable', fr.relname,
      'foreignColumns', (SELECT coalesce(json_agg(a.attname ORDER BY k.ordinality), '[]'::json)
        FROM unnest(c.confkey) WITH ORDINALITY k(num, ordinality) JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.num),
      'deleteSetColumns', (SELECT coalesce(json_agg(a.attname ORDER BY k.ordinality), '[]'::json)
        FROM unnest(c.confdelsetcols) WITH ORDINALITY k(num, ordinality) JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum = k.num),
      'updateAction', c.confupdtype, 'deleteAction', c.confdeltype, 'match', c.confmatchtype,
      'deferrable', c.condeferrable, 'deferred', c.condeferred, 'validated', c.convalidated,
      'nullsNotDistinct', coalesce(i.indnullsnotdistinct, false), 'definition', pg_get_constraintdef(c.oid, true))
      ORDER BY c.conname), '[]'::json)
      FROM pg_constraint c LEFT JOIN pg_class fr ON fr.oid = c.confrelid LEFT JOIN pg_namespace fn ON fn.oid = fr.relnamespace
      LEFT JOIN pg_index i ON i.indexrelid = c.conindid WHERE c.conrelid = r.oid))
    ORDER BY r.relname), '[]'::json)
    FROM pg_class r JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = 'public' AND r.relkind IN ('r', 'p')),
  'sequences', (SELECT coalesce(json_agg(json_build_object(
    'name', r.relname, 'dataType', format_type(s.seqtypid, -1), 'start', s.seqstart,
    'increment', s.seqincrement, 'min', s.seqmin, 'max', s.seqmax, 'cache', s.seqcache, 'cycle', s.seqcycle,
    'owner', pg_get_userbyid(r.relowner), 'acl', r.relacl::text,
    'ownedBy', (SELECT json_agg(json_build_object('table', tr.relname, 'column', a.attname, 'dependency', d.deptype))
      FROM pg_depend d JOIN pg_class tr ON tr.oid = d.refobjid
      JOIN pg_attribute a ON a.attrelid = tr.oid AND a.attnum = d.refobjsubid
      WHERE d.classid = 'pg_class'::regclass AND d.objid = r.oid AND d.deptype IN ('a', 'i')))
    ORDER BY r.relname), '[]'::json)
    FROM pg_sequence s JOIN pg_class r ON r.oid = s.seqrelid JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = 'public'),
  'indexes', (SELECT coalesce(json_agg(json_build_object('table', t.relname, 'name', i.relname,
    'definition', pg_get_indexdef(i.oid)) ORDER BY i.relname), '[]'::json)
    FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class t ON t.oid = x.indrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'public'),
  'triggers', (SELECT coalesce(json_agg(json_build_object('table', r.relname, 'name', t.tgname,
    'enabled', t.tgenabled, 'definition', pg_get_triggerdef(t.oid, true)) ORDER BY r.relname, t.tgname), '[]'::json)
    FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid JOIN pg_namespace n ON n.oid = r.relnamespace
    WHERE n.nspname = 'public' AND NOT t.tgisinternal),
  'routines', (SELECT coalesce(json_agg(json_build_object('schema', n.nspname, 'name', p.proname,
    'arguments', pg_get_function_identity_arguments(p.oid), 'owner', pg_get_userbyid(p.proowner),
    'acl', p.proacl::text, 'definition', pg_get_functiondef(p.oid)) ORDER BY n.nspname, p.proname, pg_get_function_identity_arguments(p.oid)), '[]'::json)
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN ('public', 'api') AND p.prokind IN ('f', 'p', 'w')),
  'views', (SELECT coalesce(json_agg(json_build_object('schema', n.nspname, 'name', r.relname,
    'kind', r.relkind, 'definition', pg_get_viewdef(r.oid, true)) ORDER BY n.nspname, r.relname), '[]'::json)
    FROM pg_class r JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname IN ('public', 'api') AND r.relkind IN ('v', 'm')),
  'policies', (SELECT coalesce(json_agg(json_build_object('table', r.relname, 'name', p.polname,
    'command', p.polcmd, 'permissive', p.polpermissive, 'roles', p.polroles::text,
    'using', pg_get_expr(p.polqual, p.polrelid), 'check', pg_get_expr(p.polwithcheck, p.polrelid)) ORDER BY r.relname, p.polname), '[]'::json)
    FROM pg_policy p JOIN pg_class r ON r.oid = p.polrelid JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = 'public'),
  'rules', (SELECT coalesce(json_agg(json_build_object('schema', n.nspname, 'table', r.relname,
    'name', w.rulename, 'definition', pg_get_ruledef(w.oid, true)) ORDER BY n.nspname, r.relname, w.rulename), '[]'::json)
    FROM pg_rewrite w JOIN pg_class r ON r.oid = w.ev_class JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname IN ('public', 'api')),
  'inheritanceCount', (SELECT count(*) FROM pg_inherits i JOIN pg_class r ON r.oid = i.inhrelid
    JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = 'public'),
  'largeObjectCount', (SELECT count(*) FROM pg_largeobject_metadata)
);
