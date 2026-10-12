DO $$
DECLARE
    kind_definition TEXT;
    allowed_kinds TEXT[];
    previous_kinds TEXT[] := ARRAY[
        'text', 'video', 'pdf', 'presentation', 'image', 'audio',
        'link', 'interactive', 'quiz', 'test', 'practice', 'assignment'
    ];
BEGIN
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO kind_definition
    FROM pg_constraint AS constraint_row
    JOIN pg_class AS table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace AS schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = current_schema()
      AND table_row.relname = 'course_lessons'
      AND constraint_row.conname = 'ck_course_lessons_kind'
      AND constraint_row.contype = 'c';

    IF kind_definition IS NULL THEN
        RAISE EXCEPTION 'Expected ck_course_lessons_kind constraint is missing';
    END IF;

    SELECT array_agg(DISTINCT match_row.kind[1])
    INTO allowed_kinds
    FROM regexp_matches(kind_definition, '''([^'']+)''', 'g') AS match_row(kind);

    IF allowed_kinds @> array_append(previous_kinds, 'live')
       AND array_append(previous_kinds, 'live') @> allowed_kinds
       AND cardinality(allowed_kinds) = cardinality(previous_kinds) + 1 THEN
        RETURN;
    END IF;

    IF allowed_kinds IS NULL
       OR NOT (allowed_kinds @> previous_kinds AND previous_kinds @> allowed_kinds)
       OR cardinality(allowed_kinds) <> cardinality(previous_kinds) THEN
        RAISE EXCEPTION 'Unexpected ck_course_lessons_kind definition: %', kind_definition;
    END IF;

    ALTER TABLE course_lessons
        DROP CONSTRAINT ck_course_lessons_kind,
        ADD CONSTRAINT ck_course_lessons_kind CHECK (
            kind IN (
                'text', 'video', 'pdf', 'presentation', 'image', 'audio',
                'link', 'interactive', 'quiz', 'test', 'practice', 'assignment', 'live'
            )
        );
END $$;
