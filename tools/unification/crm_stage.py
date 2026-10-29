#!/usr/bin/env python3
"""Lossless, offline PG18 CRM -> isolated PG16 typed staging. No application migration or replay."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


MIN_HEADROOM = 1024 ** 3
LEDGER = "_unification_manifest"
SETTINGS = "SET LOCAL client_encoding='UTF8'; SET LOCAL timezone='UTC'; SET LOCAL datestyle='ISO, YMD'; SET LOCAL search_path=pg_catalog; SET LOCAL standard_conforming_strings=on; SET LOCAL extra_float_digits=3;"
BUILTINS = {"text", "bool", "int2", "int4", "int8", "uuid", "date", "timestamptz", "inet", "jsonb", "bytea"}
ACTIONS = {"a": "NO ACTION", "r": "RESTRICT", "c": "CASCADE", "n": "SET NULL", "d": "SET DEFAULT"}
MATCH = {"s": "SIMPLE", "f": "FULL"}


class StageError(Exception):
    pass


def require(condition, label):
    if not condition:
        raise StageError(label)


def qi(name):
    require(isinstance(name, str) and name and "\0" not in name, "invalid SQL identifier")
    return '"' + name.replace('"', '""') + '"'


def literal(value):
    require(isinstance(value, str) and "\0" not in value, "invalid SQL text")
    return "'" + value.replace("'", "''") + "'"


def encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def hash_arg(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "expected a lowercase SHA-256")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON object key")
        result[key] = value
    return result


def parse_json(data):
    def invalid_constant(_token):
        raise StageError("non-finite JSON constant is forbidden")
    return json.loads(data, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def private_write(path, data):
    require(not path.exists(), "output already exists; refusing to overwrite a preservation artifact")
    with path.open("xb") as stream:
        path.chmod(0o600)
        stream.write(data)


class Database:
    def __init__(self, container, user, database):
        require(re.fullmatch(r"epharm-(?:unify|stage-test)-[a-zA-Z0-9_.-]+", container), "only isolated unification/test containers are allowed")
        require(re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", user), "invalid database user")
        require(re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_]*", database), "invalid database name")
        self.container, self.user, self.database = container, user, database

    def run(self, sql):
        command = ["docker", "exec", "-i", self.container, "psql", "-X", "-qAt", "--username=" + self.user,
                   "--dbname=" + self.database, "--set=ON_ERROR_STOP=1", "--set=VERBOSITY=sqlstate", "--set=SHOW_CONTEXT=never"]
        result = subprocess.run(command, input=sql, capture_output=True, text=True)
        code = re.search(r"ERROR:\s+([A-Z0-9]{5})\b", result.stderr)
        require(result.returncode == 0, "PostgreSQL operation failed; transaction aborted; source values suppressed"
                + ("; SQLSTATE=" + code.group(1) if code else ""))
        return result.stdout

    def readonly(self, sql):
        return self.run("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;" + SETTINGS + sql + "COMMIT;")

    def inspect(self):
        command = ["docker", "inspect", "--format", '{{json .Id}} {{json .HostConfig.NetworkMode}} {{json .State.Running}} {{json .NetworkSettings.Ports}}', self.container]
        result = subprocess.run(command, capture_output=True, text=True)
        require(result.returncode == 0, "cannot inspect isolated database container")
        parts = result.stdout.split(maxsplit=3)
        require(len(parts) == 4 and parse_json(parts[1]) == "none" and parse_json(parts[2]) is True,
                "database container must be running with network=none")
        ports = parse_json(parts[3])
        require(not ports or all(value is None for value in ports.values()), "published database ports are forbidden")
        info = parse_json(self.readonly("SELECT json_build_object('database',current_database(),'oid',(SELECT oid FROM pg_database WHERE datname=current_database()),'systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()),'versionNum',current_setting('server_version_num')::integer,'dataDirectory',current_setting('data_directory'),'collate',(SELECT datcollate FROM pg_database WHERE datname=current_database()),'ctype',(SELECT datctype FROM pg_database WHERE datname=current_database()),'localeProvider',(SELECT datlocprovider FROM pg_database WHERE datname=current_database()),'sha256Available',to_regprocedure('pg_catalog.sha256(bytea)') IS NOT NULL);"))
        identity = {key: info[key] for key in ("database", "oid", "systemIdentifier", "versionNum")}
        identity["containerId"] = parse_json(parts[0])
        info["identitySha256"] = digest(encoded(identity))
        result = subprocess.run(["docker", "exec", self.container, "df", "-Pk", info["dataDirectory"]], capture_output=True, text=True)
        require(result.returncode == 0, "cannot inspect candidate filesystem capacity")
        lines = result.stdout.strip().splitlines()
        require(len(lines) >= 2 and len(lines[-1].split()) >= 6, "unsupported df output")
        info["freeBytes"] = int(lines[-1].split()[3]) * 1024
        return info


def metadata_sql():
    return Path(__file__).with_name("crm_stage_metadata.sql").read_text()


def schema_hash(metadata):
    stable = {key: value for key, value in metadata.items() if key != "database"}
    stable["databaseLocale"] = {key: metadata["database"][key] for key in ("encoding", "collate", "ctype", "localeProvider")}
    return digest(encoded(stable))


def primary_columns(table):
    keys = [c for c in table["constraints"] if c["kind"] == "p"]
    require(len(keys) == 1 and keys[0]["columns"], "every source table must have exactly one primary key")
    return keys[0]["columns"]


def column_type(column, enums, schema):
    name = column["typeName"]
    if column["typeSchema"] == "public":
        require(name in enums, "unsupported source-defined column type")
        return qi(schema) + "." + qi(name)
    require(column["typeSchema"] == "pg_catalog", "unsupported external column type")
    if name in BUILTINS:
        if name == "timestamptz" and column["typeModifier"] != -1:
            precision = column["typeModifier"]
            require(type(precision) is int and 0 <= precision <= 6, "unsupported timestamp precision")
            return f"pg_catalog.timestamptz({precision})"
        require(column["typeModifier"] == -1, "unsupported builtin type modifier")
        return "pg_catalog." + qi(name)
    if name in {"_date", "_uuid"}:
        require(column["typeModifier"] == -1, "unsupported array type modifier")
        return "pg_catalog." + qi(name[1:]) + "[]"
    if name in {"varchar", "bpchar"}:
        length = column["characterLength"]
        if name == "varchar" and column["typeModifier"] == -1 and length is None:
            return "pg_catalog.varchar"
        require(type(length) is int and length > 0, "unsupported character type modifier")
        return "pg_catalog." + qi(name) + f"({length})"
    if name == "numeric":
        if column["typeModifier"] == -1:
            return "pg_catalog.numeric"
        precision, scale = column["numericPrecision"], column["numericScale"]
        require(type(precision) is int and type(scale) is int and 1 <= precision <= 1000 and -1000 <= scale <= 1000,
                "unsupported numeric type modifier")
        return f"pg_catalog.numeric({precision},{scale})"
    raise StageError("unsupported builtin column type")


def validate_metadata(metadata, expected_tables):
    require(metadata["database"]["versionNum"] // 10000 == 18, "source must be restored PostgreSQL 18")
    require(metadata["database"]["encoding"] == "UTF8", "source must use UTF8")
    require(type(metadata["database"]["sizeBytes"]) is int and metadata["database"]["sizeBytes"] > 0,
            "source footprint metadata must be positive")
    require(metadata["inheritanceCount"] == 0 and metadata["largeObjectCount"] == 0, "inheritance/large objects require a separate preservation implementation")
    tables = metadata["tables"]
    require(len(tables) == expected_tables and len({t["name"] for t in tables}) == expected_tables,
            "source table inventory differs from expected count")
    enums = {e["name"] for e in metadata["enums"]}
    require(len(enums) == len(metadata["enums"]), "duplicate enum identity")
    for enum in metadata["enums"]:
        require(enum["labels"] and len(set(enum["labels"])) == len(enum["labels"]), "invalid enum labels")
    for table in tables:
        require(table["kind"] == "r" and table["name"] != LEDGER, "unsupported source table kind/name")
        columns = table["columns"]
        names = {c["name"] for c in columns}
        require(columns and len(names) == len(columns), "invalid source column inventory")
        require([c["position"] for c in columns] == list(range(1, len(columns) + 1)),
                "dropped/irregular physical columns require explicit preservation")
        require(set(primary_columns(table)) <= names, "primary key refers to a missing column")
        for column in columns:
            column_type(column, enums, "crm_stage_typecheck")
            require(column["generated"] == "", "generated columns require explicit PG16 handling")
            require(column["collationName"] in (None, "default") and column["collationSchema"] in (None, "pg_catalog"),
                    "nondefault column collation needs explicit preservation")
        for constraint in table["constraints"]:
            require(constraint["kind"] in {"p", "u", "f", "c", "n"}, "unsupported constraint kind")
            require(set(constraint["columns"]) <= names, "constraint refers to a missing column")
            if constraint["kind"] == "f":
                require(constraint["foreignSchema"] == "public" and constraint["foreignTable"] in {t["name"] for t in tables},
                        "foreign key target is outside the preserved source tables")
                foreign = next(t for t in tables if t["name"] == constraint["foreignTable"])
                require(len(constraint["columns"]) == len(constraint["foreignColumns"]) > 0
                        and set(constraint["foreignColumns"]) <= {c["name"] for c in foreign["columns"]},
                        "foreign key target column inventory differs")
                require(constraint["match"] in MATCH and constraint["updateAction"] in ACTIONS and constraint["deleteAction"] in ACTIONS,
                        "unsupported foreign key action")


def row_frame(values):
    return b"".join(b"N;" if value is None else b"S" + str(len(value.encode())).encode() + b":" + value.encode() + b";" for value in values)


def table_checksum(table, rows):
    columns = [c["name"] for c in table["columns"]]
    primary = [columns.index(name) for name in primary_columns(table)]
    row_hashes, key_hashes = [], []
    for row in rows:
        require(isinstance(row, list) and len(row) == len(columns), "row column count differs from source metadata")
        require(all(value is None or isinstance(value, str) and "\0" not in value for value in row), "invalid typed-text row value")
        require(all(row[index] is not None for index in primary), "null source primary key")
        row_hashes.append(digest(row_frame(row)))
        key_hashes.append(digest(row_frame([row[index] for index in primary])))
    require(len(set(key_hashes)) == len(key_hashes), "duplicate source primary key")
    return {"table": table["name"], "rowCount": len(rows),
            "rowSha256": digest("".join(h + "\n" for h in sorted(row_hashes)).encode()),
            "primaryKeySha256": digest("".join(h + "\n" for h in sorted(key_hashes)).encode())}


def data_query(metadata, schema):
    selects = []
    for table in metadata["tables"]:
        values = [f"CASE WHEN {qi(c['name'])} IS NULL THEN NULL ELSE pg_catalog.format('%s',{qi(c['name'])}) END" for c in table["columns"]]
        selects.append("SELECT json_build_object('kind','row','table'," + literal(table["name"]) + ",'values',array_to_json(ARRAY[" + ",".join(values) + "]::text[]))::text FROM " + qi(schema) + "." + qi(table["name"]))
    return "COPY (" + " UNION ALL ".join(selects) + ") TO STDOUT WITH (FORMAT csv, DELIMITER E'\\t', QUOTE E'\\x01', ESCAPE E'\\x01');"


def export_artifact(db, expected_schema_sha, backup, expected_backup_sha, output, expected_tables):
    info = db.inspect()
    require(info["versionNum"] // 10000 == 18, "source must be PostgreSQL 18")
    require(file_hash(backup) == hash_arg(expected_backup_sha), "source backup checksum differs from pinned hash")
    metadata = parse_json(db.readonly(metadata_sql()))
    validate_metadata(metadata, expected_tables)
    require(schema_hash(metadata) == hash_arg(expected_schema_sha), "source schema checksum differs from pinned hash")
    sequence_queries = "".join("SELECT json_build_object('kind','sequence','name'," + literal(seq["name"]) + ",'lastValue',last_value,'isCalled',is_called) FROM public." + qi(seq["name"]) + ";" for seq in metadata["sequences"])
    lines = db.readonly(metadata_sql() + sequence_queries + data_query(metadata, "public")).splitlines()
    actual_metadata = parse_json(lines[0])
    require(schema_hash(actual_metadata) == expected_schema_sha, "source schema changed before repeatable-read snapshot")
    data = {t["name"]: [] for t in metadata["tables"]}
    states = []
    for line in lines[1:]:
        item = parse_json(line)
        if item["kind"] == "sequence":
            states.append({k: v for k, v in item.items() if k != "kind"})
        else:
            require(item["kind"] == "row" and item["table"] in data, "unexpected snapshot frame")
            data[item["table"]].append(item["values"])
    require({s["name"] for s in states} == {s["name"] for s in metadata["sequences"]}
            and len(states) == len(metadata["sequences"]), "sequence state inventory differs")
    checksums = [table_checksum(t, data[t["name"]]) for t in metadata["tables"]]
    artifact = {"formatVersion": 1, "capturedAt": datetime.now(timezone.utc).isoformat(),
                "sourceIdentitySha256": info["identitySha256"], "sourceSchemaSha256": expected_schema_sha,
                "sourceBackupSha256": expected_backup_sha, "metadata": actual_metadata, "sequenceStates": states,
                "tables": data, "checksums": checksums}
    private_write(output, encoded(artifact))
    return {"artifactSha256": file_hash(output), "sourceSchemaSha256": expected_schema_sha,
            "tableCount": len(data), "rowCount": sum(c["rowCount"] for c in checksums), "bytes": output.stat().st_size}


def copy_value(value):
    if value is None:
        return "\\N"
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r").replace("\b", "\\b").replace("\f", "\\f").replace("\v", "\\v")


def constraint_sql(table, constraint, schema):
    columns = ",".join(qi(name) for name in constraint["columns"])
    if constraint["kind"] in {"p", "u"}:
        clause = "PRIMARY KEY" if constraint["kind"] == "p" else "UNIQUE" + (" NULLS NOT DISTINCT" if constraint["nullsNotDistinct"] else "")
        clause += " (" + columns + ")"
    else:
        clause = "FOREIGN KEY (" + columns + ") REFERENCES " + qi(schema) + "." + qi(constraint["foreignTable"])
        clause += " (" + ",".join(qi(name) for name in constraint["foreignColumns"]) + ") MATCH " + MATCH[constraint["match"]]
        clause += " ON UPDATE " + ACTIONS[constraint["updateAction"]] + " ON DELETE " + ACTIONS[constraint["deleteAction"]]
        if constraint["deleteSetColumns"]:
            clause += " (" + ",".join(qi(name) for name in constraint["deleteSetColumns"]) + ")"
    if constraint["deferrable"]:
        clause += " DEFERRABLE INITIALLY " + ("DEFERRED" if constraint["deferred"] else "IMMEDIATE")
    if constraint["kind"] == "f" and not constraint["validated"]:
        clause += " NOT VALID"
    return "ALTER TABLE " + qi(schema) + "." + qi(table["name"]) + " ADD CONSTRAINT " + qi(constraint["name"]) + " " + clause + ";\n"


def expected_signature(metadata, schema):
    tables = []
    for table in metadata["tables"]:
        columns = [{k: c[k] for k in ("name", "position", "typeSchema", "typeName", "typeModifier", "notNull")} for c in table["columns"]]
        for column in columns:
            if column["typeSchema"] == "public":
                column["typeSchema"] = schema
            column.update({"identity": "", "generated": "", "default": None})
        constraints = []
        for c in table["constraints"]:
            if c["kind"] not in {"p", "u", "f"}:
                continue
            item = {k: c[k] for k in ("name", "kind", "columns", "deferrable", "deferred", "validated")}
            if c["kind"] == "f":
                item.update({k: c[k] for k in ("foreignTable", "foreignColumns", "deleteSetColumns", "updateAction", "deleteAction", "match")})
                item["foreignSchema"] = schema
            else:
                item["nullsNotDistinct"] = c["nullsNotDistinct"]
            constraints.append(item)
        tables.append({"name": table["name"], "columns": columns, "constraints": constraints,
                       "rowSecurity": False, "forceRowSecurity": False})
    return {"enums": metadata["enums"], "tables": tables, "userTriggers": 0, "routines": 0, "sequences": 0, "views": 0}


def signature_query(schema):
    # This is our catalogue query, not a rewrite or execution of source DDL.
    return """SELECT jsonb_build_object('enums',coalesce((SELECT jsonb_agg(jsonb_build_object('name',t.typname,'labels',
      (SELECT jsonb_agg(enumlabel ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid=t.oid)) ORDER BY t.typname)
      FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname=SCHEMA AND t.typtype='e'),'[]'::jsonb),
      'tables',coalesce((SELECT jsonb_agg(jsonb_build_object('name',r.relname,'rowSecurity',r.relrowsecurity,'forceRowSecurity',r.relforcerowsecurity,'columns',
      (SELECT jsonb_agg(jsonb_build_object('name',a.attname,'position',a.attnum,'typeSchema',tn.nspname,'typeName',t.typname,
      'typeModifier',a.atttypmod,'notNull',a.attnotnull,'identity',a.attidentity,'generated',a.attgenerated,'default',pg_get_expr(ad.adbin,ad.adrelid)) ORDER BY a.attnum)
      FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace tn ON tn.oid=t.typnamespace
      LEFT JOIN pg_attrdef ad ON ad.adrelid=a.attrelid AND ad.adnum=a.attnum WHERE a.attrelid=r.oid AND a.attnum>0 AND NOT a.attisdropped),
      'constraints',coalesce((SELECT jsonb_agg(jsonb_build_object('name',c.conname,'kind',c.contype,'columns',
      (SELECT coalesce(jsonb_agg(a.attname ORDER BY k.ordinality),'[]'::jsonb) FROM unnest(c.conkey) WITH ORDINALITY k(num,ordinality)
      JOIN pg_attribute a ON a.attrelid=r.oid AND a.attnum=k.num),'deferrable',c.condeferrable,'deferred',c.condeferred,'validated',c.convalidated)
      || CASE WHEN c.contype='f' THEN jsonb_build_object('foreignSchema',fn.nspname,'foreignTable',fr.relname,'foreignColumns',
      (SELECT jsonb_agg(a.attname ORDER BY k.ordinality) FROM unnest(c.confkey) WITH ORDINALITY k(num,ordinality)
      JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num),'deleteSetColumns',
      (SELECT coalesce(jsonb_agg(a.attname ORDER BY k.ordinality),'[]'::jsonb) FROM unnest(c.confdelsetcols) WITH ORDINALITY k(num,ordinality)
      JOIN pg_attribute a ON a.attrelid=r.oid AND a.attnum=k.num),'updateAction',c.confupdtype,'deleteAction',c.confdeltype,'match',c.confmatchtype)
      ELSE jsonb_build_object('nullsNotDistinct',coalesce(i.indnullsnotdistinct,false)) END ORDER BY c.conname)
      FROM pg_constraint c LEFT JOIN pg_class fr ON fr.oid=c.confrelid LEFT JOIN pg_namespace fn ON fn.oid=fr.relnamespace
      LEFT JOIN pg_index i ON i.indexrelid=c.conindid
      WHERE c.conrelid=r.oid AND c.contype IN ('p','u','f')),'[]'::jsonb)) ORDER BY r.relname)
      FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=SCHEMA AND r.relkind='r'
      AND r.relname<>LEDGER),'[]'::jsonb),
      'userTriggers',(SELECT count(*) FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=SCHEMA AND NOT t.tgisinternal),
      'routines',(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=SCHEMA),
      'sequences',(SELECT count(*) FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=SCHEMA AND r.relkind='S'),
      'views',(SELECT count(*) FROM pg_class r JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=SCHEMA AND r.relkind IN ('v','m')))""".replace("SCHEMA", literal(schema)).replace("LEDGER", literal(LEDGER))


def hash_expression(columns):
    values = ["CASE WHEN " + qi(name) + " IS NULL THEN 'N;' ELSE 'S'||octet_length(pg_catalog.format('%s'," + qi(name) + "))::text||':'||pg_catalog.format('%s'," + qi(name) + ")||';' END" for name in columns]
    return "encode(pg_catalog.sha256(convert_to(" + "||".join("(" + v + ")" for v in values) + ",'UTF8')),'hex')"


def verification_sql(artifact, schema, artifact_sha, include_ledger):
    statements = []
    if include_ledger:
        statements.append("IF (SELECT artifact_sha256 FROM " + qi(schema) + "." + qi(LEDGER) + ") IS DISTINCT FROM " + literal(artifact_sha) + " THEN RAISE EXCEPTION 'staging provenance mismatch'; END IF;")
        manifest = {key: value for key, value in artifact.items() if key != "tables"}
        statements.append("IF (SELECT manifest FROM " + qi(schema) + "." + qi(LEDGER) + ") IS DISTINCT FROM " + literal(encoded(manifest).decode()) + "::jsonb THEN RAISE EXCEPTION 'staging manifest mismatch'; END IF;")
    statements.append("IF (" + signature_query(schema) + ") IS DISTINCT FROM " + literal(encoded(expected_signature(artifact["metadata"], schema)).decode()) + "::jsonb THEN RAISE EXCEPTION 'staging schema mismatch'; END IF;")
    by_name = {c["table"]: c for c in artifact["checksums"]}
    for table in artifact["metadata"]["tables"]:
        expected = by_name[table["name"]]
        rows = hash_expression([c["name"] for c in table["columns"]])
        keys = hash_expression(primary_columns(table))
        query = "SELECT count(*),encode(pg_catalog.sha256(convert_to(coalesce(string_agg(checks.rh||E'\\n','' ORDER BY checks.rh COLLATE pg_catalog.\"C\"),''),'UTF8')),'hex'),encode(pg_catalog.sha256(convert_to(coalesce(string_agg(checks.kh||E'\\n','' ORDER BY checks.kh COLLATE pg_catalog.\"C\"),''),'UTF8')),'hex') INTO n,rh,kh FROM (SELECT " + rows + " AS rh," + keys + " AS kh FROM " + qi(schema) + "." + qi(table["name"]) + ") checks;"
        statements.append(query + "IF n<>" + str(expected["rowCount"]) + " OR rh<>" + literal(expected["rowSha256"]) + " OR kh<>" + literal(expected["primaryKeySha256"]) + " THEN RAISE EXCEPTION 'staging typed row checksum mismatch'; END IF;")
    body = "DECLARE n bigint; rh text; kh text; BEGIN " + "\n".join(statements) + " END"
    tag = "$stage_" + digest(body.encode())[:16] + "$"
    while tag in body:
        tag = tag[:-1] + "x$"
    return "DO " + tag + body + tag + ";"


def load_sql(artifact, schema, artifact_sha):
    metadata = artifact["metadata"]
    enums = {e["name"] for e in metadata["enums"]}
    commands = ["BEGIN;", SETTINGS, "CREATE SCHEMA " + qi(schema) + "; REVOKE ALL ON SCHEMA " + qi(schema) + " FROM PUBLIC;"]
    for enum in metadata["enums"]:
        commands.append("CREATE TYPE " + qi(schema) + "." + qi(enum["name"]) + " AS ENUM (" + ",".join(literal(v) for v in enum["labels"]) + ");")
    for table in metadata["tables"]:
        columns = [qi(c["name"]) + " " + column_type(c, enums, schema) + (" NOT NULL" if c["notNull"] else "") for c in table["columns"]]
        commands.append("CREATE TABLE " + qi(schema) + "." + qi(table["name"]) + " (" + ",".join(columns) + ");")
        commands.append("COPY " + qi(schema) + "." + qi(table["name"]) + " (" + ",".join(qi(c["name"]) for c in table["columns"]) + ") FROM STDIN WITH (FORMAT text);\n" + "".join("\t".join(copy_value(v) for v in row) + "\n" for row in artifact["tables"][table["name"]]) + "\\.\n")
    for kind in ("p", "u", "f"):
        for table in metadata["tables"]:
            commands.extend(constraint_sql(table, c, schema) for c in table["constraints"] if c["kind"] == kind)
    commands.append(verification_sql(artifact, schema, artifact_sha, False))
    commands.append("CREATE TABLE " + qi(schema) + "." + qi(LEDGER) + " (artifact_sha256 text PRIMARY KEY, manifest jsonb NOT NULL);")
    manifest = {key: value for key, value in artifact.items() if key != "tables"}
    commands.append("INSERT INTO " + qi(schema) + "." + qi(LEDGER) + " VALUES (" + literal(artifact_sha) + "," + literal(encoded(manifest).decode()) + "::jsonb);")
    commands.append("REVOKE ALL ON ALL TABLES IN SCHEMA " + qi(schema) + " FROM PUBLIC; COMMIT;")
    return "\n".join(commands)


def validate_artifact(artifact, expected_tables):
    require(set(artifact) == {"formatVersion", "capturedAt", "sourceIdentitySha256", "sourceSchemaSha256",
                              "sourceBackupSha256", "metadata", "sequenceStates", "tables", "checksums"},
            "unsupported or missing artifact fields")
    require(type(artifact["formatVersion"]) is int and artifact["formatVersion"] == 1, "unsupported staging artifact format")
    metadata = artifact["metadata"]
    validate_metadata(metadata, expected_tables)
    require(schema_hash(metadata) == artifact["sourceSchemaSha256"], "artifact source metadata checksum differs")
    require(set(artifact["tables"]) == {t["name"] for t in metadata["tables"]}, "artifact table coverage differs")
    computed = [table_checksum(t, artifact["tables"][t["name"]]) for t in metadata["tables"]]
    require(computed == artifact["checksums"], "artifact row/primary-key checksums differ")
    require({s["name"] for s in artifact["sequenceStates"]} == {s["name"] for s in metadata["sequences"]}
            and len(artifact["sequenceStates"]) == len(metadata["sequences"]), "artifact sequence inventory differs")
    require(all(set(s) == {"name", "lastValue", "isCalled"} and type(s["lastValue"]) is int
                and type(s["isCalled"]) is bool for s in artifact["sequenceStates"]), "invalid inert sequence state")
    for key in ("sourceIdentitySha256", "sourceSchemaSha256", "sourceBackupSha256"):
        hash_arg(artifact[key])


def load_artifact(db, artifact_path, expected_sha, expected_target, expected_tables, min_headroom, report):
    require(db.database not in {"epharm", "merch_audit", "postgres", "template0", "template1"}
            and re.fullmatch(r"epharm_[a-z0-9_]*candidate[a-z0-9_]*", db.database), "writes require a dedicated epharm_*candidate database")
    require(type(min_headroom) is int and min_headroom >= MIN_HEADROOM, "minimum headroom cannot be below 1 GiB")
    require(report.resolve() != artifact_path.resolve() and not report.is_symlink(), "report must not replace a source artifact")
    if report.exists():
        previous = parse_json(report.read_bytes())
        require(previous.get("artifactSha256") == expected_sha and previous.get("targetIdentitySha256") == expected_target,
                "existing report belongs to a different source/target")
    require(file_hash(artifact_path) == hash_arg(expected_sha), "artifact checksum differs from pinned source hash")
    require(artifact_path.stat().st_mode & 0o077 == 0, "private source artifact must have mode 0600 or stricter")
    artifact = parse_json(artifact_path.read_bytes())
    validate_artifact(artifact, expected_tables)
    info = db.inspect()
    require(info["identitySha256"] == hash_arg(expected_target), "candidate identity differs from approved target pin")
    require(info["versionNum"] // 10000 == 16 and info["sha256Available"], "target must be PostgreSQL16 with core sha256")
    for field in ("collate", "ctype", "localeProvider"):
        require(info[field] == artifact["metadata"]["database"][field], "source and candidate database locales differ")
    schema = "crm_stage_" + expected_sha[:16]
    exists = db.readonly("SELECT to_regnamespace(" + literal(schema) + ") IS NOT NULL;").strip()
    require(exists in {"t", "f"}, "unsupported candidate schema probe")
    budget = max(artifact["metadata"]["database"]["sizeBytes"] * 4, artifact_path.stat().st_size * 4)
    require(info["freeBytes"] >= min_headroom + (0 if exists == "t" else budget), "candidate storage guard failed: need strict headroom plus 4x source footprint")
    if exists == "t":
        db.readonly(verification_sql(artifact, schema, expected_sha, True))
        mode = "verified_existing_noop"
    else:
        db.run(load_sql(artifact, schema, expected_sha))
        mode = "imported_and_verified"
    constraints = Counter(c["kind"] for t in artifact["metadata"]["tables"] for c in t["constraints"])
    summary = {"state": "source_confirmed", "functionalMergeAccepted": False, "mode": mode,
               "artifactSha256": expected_sha, "sourceBackupSha256": artifact["sourceBackupSha256"],
               "sourceSchemaSha256": artifact["sourceSchemaSha256"], "targetIdentitySha256": expected_target,
               "sourceVersion": artifact["metadata"]["database"]["version"], "targetMajorVersion": 16,
               "tables": len(artifact["tables"]), "columns": sum(len(t["columns"]) for t in artifact["metadata"]["tables"]),
               "rows": sum(c["rowCount"] for c in artifact["checksums"]), "foreignKeys": constraints["f"],
               "primaryKeys": constraints["p"], "uniqueConstraints": constraints["u"],
               "inertCheckDefinitions": constraints["c"], "inertRoutines": len(artifact["metadata"]["routines"]),
               "inertTriggers": len(artifact["metadata"]["triggers"]), "inertSequenceStates": len(artifact["sequenceStates"]),
               "enumTypes": len(artifact["metadata"]["enums"]), "minHeadroomBytes": min_headroom,
               "sourceFootprintBudgetBytes": budget, "freeBytesBefore": info["freeBytes"],
               "tableChecksums": artifact["checksums"],
               "boundary": "Typed inert staging only. No source/application writes, legacy-code execution, reward/API replay, file duplication, account/person merge or release-gate acceptance."}
    if report.exists():
        report.write_bytes(encoded(summary))
        report.chmod(0o600)
    else:
        private_write(report, encoded(summary))
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("inspect", "export", "load"):
        p = commands.add_parser(name)
        p.add_argument("--container", required=True)
        p.add_argument("--user", required=True)
        p.add_argument("--database", required=True)
        if name != "inspect":
            p.add_argument("--expected-tables", type=int, default=57)
        if name == "export":
            p.add_argument("--expected-source-schema-sha256", required=True)
            p.add_argument("--source-backup", required=True, type=Path)
            p.add_argument("--expected-source-backup-sha256", required=True)
            p.add_argument("--artifact", required=True, type=Path)
        if name == "load":
            p.add_argument("--artifact", required=True, type=Path)
            p.add_argument("--expected-artifact-sha256", required=True)
            p.add_argument("--expected-target-identity-sha256", required=True)
            p.add_argument("--min-headroom-bytes", type=int, default=MIN_HEADROOM)
            p.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        db = Database(args.container, args.user, args.database)
        if args.command == "inspect":
            info = db.inspect()
            result = {key: info[key] for key in ("database", "identitySha256", "versionNum", "freeBytes")}
            if info["versionNum"] // 10000 == 18:
                metadata = parse_json(db.readonly(metadata_sql()))
                validate_metadata(metadata, len(metadata["tables"]))
                result.update({"sourceSchemaSha256": schema_hash(metadata), "tables": len(metadata["tables"])})
        elif args.command == "export":
            result = export_artifact(db, args.expected_source_schema_sha256, args.source_backup,
                                     args.expected_source_backup_sha256, args.artifact, args.expected_tables)
        else:
            result = load_artifact(db, args.artifact, args.expected_artifact_sha256,
                                   args.expected_target_identity_sha256, args.expected_tables, args.min_headroom_bytes, args.report)
        print(json.dumps(result, sort_keys=True))
        return 0
    except StageError as error:
        print("CRM staging: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, RuntimeError):
        # Database errors and malformed row values can contain credentials or personal data.
        print("CRM staging failed; no source values logged. Check pins, schema support, constraints and capacity.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
