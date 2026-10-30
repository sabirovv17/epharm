-- An exact upload retry by the same author must not create another draft.
CREATE TABLE course_package_import_receipts (
    author_id uuid NOT NULL REFERENCES admin_users(id),
    digest varchar(64) NOT NULL,
    course_id varchar(64) NOT NULL REFERENCES courses(id),
    program_id uuid REFERENCES training_programs(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (author_id, digest)
);
