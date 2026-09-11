"""Fail-closed adapters for SMB acquisition and ClickHouse execution.

Secrets are supplied by systemd environment files or protected credential files.
They are never embedded in this module or placed in subprocess arguments.
"""

import os
import re
import stat
import subprocess
from datetime import datetime
from pathlib import Path


FILE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.csv$")
IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
CONTAINER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


def _absolute_path(name, default):
    value = os.getenv(name, default)
    path = Path(value)
    if not path.is_absolute():
        raise RuntimeError(f"{name} must be an absolute path")
    return path


def _identifier(name, value):
    if not IDENTIFIER_RE.fullmatch(value):
        raise RuntimeError(f"{name} is not a valid identifier")
    return value


ROOT = _absolute_path("PIM_ROOT", "/var/lib/pim-dashboard")
STAGING = _absolute_path("PIM_STAGING_ROOT", str(ROOT / "staging"))
REJECTS = _absolute_path("PIM_REJECTS_ROOT", str(ROOT / "rejects"))
SMB_SHARE = os.getenv("PIM_SMB_SHARE", "//inkfs1/inkarfs")
SMB_DIR = os.getenv(
    "PIM_SMB_DIR",
    r"Автоматические процессы компании\4.StandartN_API\auto_download_daily",
)
SMB_CREDENTIALS = _absolute_path(
    "PIM_SMB_CREDENTIALS_FILE", "/etc/pim-dashboard/smb-credentials"
)
CLICKHOUSE_CONTAINER = os.getenv("CLICKHOUSE_CONTAINER", "pim-clickhouse")
DB = _identifier("CLICKHOUSE_DB", os.getenv("CLICKHOUSE_DB", "pharmacy_analytics"))
MAX_SOURCE_BYTES = int(os.getenv("PIM_MAX_SOURCE_BYTES", str(64 * 1024**3)))


def _required_env(name):
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"required environment variable {name} is missing")
    return value


def _protected_file(path, *, private):
    path = Path(path)
    try:
        metadata = path.lstat()
    except FileNotFoundError as exc:
        raise RuntimeError(f"required protected file is missing: {path}") from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise RuntimeError(f"protected path must be a regular non-symlink file: {path}")
    forbidden = 0o077 if private else 0o022
    if stat.S_IMODE(metadata.st_mode) & forbidden:
        expectation = "group/world access" if private else "group/world write access"
        raise RuntimeError(f"protected file permits {expectation}: {path}")
    return path


def _validate_source_name(name):
    if not FILE_RE.fullmatch(name):
        raise RuntimeError("invalid Standard N source file name")
    try:
        datetime.strptime(name[:10], "%Y-%m-%d")
    except ValueError as exc:
        raise RuntimeError("invalid Standard N source date") from exc
    return name


def _validate_smb_config():
    if not re.fullmatch(r"//[^/\\\s]+/[^/\\\s]+", SMB_SHARE):
        raise RuntimeError("PIM_SMB_SHARE must be a single //server/share path")
    if not SMB_DIR or any(character in SMB_DIR for character in ('"', ";", "\r", "\n")):
        raise RuntimeError("PIM_SMB_DIR contains an unsafe smbclient command character")
    return _protected_file(SMB_CREDENTIALS, private=True)


def _validate_staging():
    if STAGING.exists() and (STAGING.is_symlink() or not STAGING.is_dir()):
        raise RuntimeError("PIM staging root must be a non-symlink directory")
    STAGING.mkdir(parents=True, exist_ok=True, mode=0o700)
    return STAGING


def client_command(query):
    """Build a ClickHouse command without placing credentials in argv.

    ``docker exec -e NAME`` copies the already-protected service environment
    into the container. clickhouse-client consumes CLICKHOUSE_USER and
    CLICKHOUSE_PASSWORD directly.
    """
    _required_env("CLICKHOUSE_USER")
    _required_env("CLICKHOUSE_PASSWORD")
    if not CONTAINER_RE.fullmatch(CLICKHOUSE_CONTAINER):
        raise RuntimeError("CLICKHOUSE_CONTAINER is invalid")
    if not isinstance(query, str) or not query.strip():
        raise RuntimeError("ClickHouse query is empty")
    return [
        "docker",
        "exec",
        "-i",
        "-e",
        "CLICKHOUSE_USER",
        "-e",
        "CLICKHOUSE_PASSWORD",
        CLICKHOUSE_CONTAINER,
        "clickhouse-client",
        "--async_insert",
        "0",
        "--wait_for_async_insert",
        "0",
        "--format",
        "TabSeparatedRaw",
        "--query",
        query,
    ]


def remote_files():
    credentials = _validate_smb_config()
    command = [
        "smbclient",
        SMB_SHARE,
        "-A",
        str(credentials),
        "-c",
        f'cd "{SMB_DIR}"; ls *.csv',
    ]
    result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=120)
    files = {}
    for line in result.stdout.splitlines():
        match = re.match(r"^\s+(\d{4}-\d{2}-\d{2}\.csv)\s+\S+\s+(\d+)\s+", line)
        if not match:
            continue
        name = _validate_source_name(match.group(1))
        size = int(match.group(2))
        if size <= 0 or size > MAX_SOURCE_BYTES:
            raise RuntimeError(f"SMB source size is outside policy for {name}")
        if name in files and files[name] != size:
            raise RuntimeError(f"SMB returned conflicting sizes for {name}")
        files[name] = size
    if not files:
        raise RuntimeError("No Standard N CSV files were found on SMB")
    return files


def download_file(file_name, expected_size):
    name = _validate_source_name(file_name)
    if not isinstance(expected_size, int) or expected_size <= 0 or expected_size > MAX_SOURCE_BYTES:
        raise RuntimeError("invalid expected Standard N source size")
    credentials = _validate_smb_config()
    staging = _validate_staging()
    final_path = staging / name
    partial_path = staging / f"{name}.part"
    for candidate in (final_path, partial_path):
        if candidate.exists() and (candidate.is_symlink() or not candidate.is_file()):
            raise RuntimeError(f"unsafe staging path: {candidate}")
    partial_path.unlink(missing_ok=True)
    command = [
        "smbclient",
        SMB_SHARE,
        "-A",
        str(credentials),
        "-c",
        f'cd "{SMB_DIR}"; get "{name}" "{partial_path}"',
    ]
    subprocess.run(command, check=True, timeout=1800)
    if not partial_path.is_file() or partial_path.is_symlink():
        raise RuntimeError("SMB download did not produce a regular staged file")
    if partial_path.stat().st_size != expected_size:
        raise RuntimeError(
            f"downloaded size differs for {name}: {partial_path.stat().st_size} != {expected_size}"
        )
    partial_path.replace(final_path)
    return final_path


def _date_argument(name, value):
    if value is None:
        return None
    try:
        return datetime.strptime(value, "%Y-%m-%d").date().isoformat()
    except ValueError as exc:
        raise RuntimeError(f"{name} must use YYYY-MM-DD") from exc


def select_files(files, args):
    names = sorted(_validate_source_name(name) for name in files)
    if args.file:
        selected = _validate_source_name(args.file)
        if selected not in files:
            raise RuntimeError(f"source file is absent from SMB: {selected}")
        return [selected]
    from_date = _date_argument("--from-date", args.from_date)
    to_date = _date_argument("--to-date", args.to_date)
    if from_date and to_date and from_date > to_date:
        raise RuntimeError("--from-date must not be later than --to-date")
    if from_date:
        names = [name for name in names if name[:10] >= from_date]
    if to_date:
        names = [name for name in names if name[:10] <= to_date]
    if args.repair_rejects:
        names = [name for name in names if (REJECTS / f"{name}.rejects.jsonl").is_file()]
    if args.latest is not None:
        if args.latest <= 0:
            raise RuntimeError("--latest must be greater than zero")
        names = names[-args.latest :]
    return names
