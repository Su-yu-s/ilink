"""Back up the live MySQL schema and clone it to a new evaluation schema.

This command never modifies or drops the source schema. It refuses a target
that already exists. The JSON backup stays outside Git and may contain user data.
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
from decimal import Decimal
from pathlib import Path

import mysql.connector
import yaml


def resolve(value):
    value = str(value)
    for _ in range(8):
        updated = re.sub(r"\$\{([^{}:]+)(?::([^{}]*))?\}",
                         lambda match: os.getenv(match.group(1), match.group(2) or ""), value)
        if updated == value:
            break
        value = updated
    return value


def connection_settings(config_dir: Path):
    base = yaml.safe_load((config_dir / "application.yml").read_text(encoding="utf-8"))
    dev = yaml.safe_load((config_dir / "application-dev.yml").read_text(encoding="utf-8"))
    source = base["spring"]["datasource"]
    url = resolve(source["url"])
    match = re.search(r"jdbc:mysql://([^/:?]+)(?::(\d+))?/([^?]+)", url)
    if not match:
        raise ValueError("unsupported MySQL URL")
    return dict(host=match.group(1), port=int(match.group(2) or 3306),
                database=match.group(3), user=resolve(source["username"]),
                password=resolve(dev["spring"]["datasource"]["password"]))


def encode(value):
    if isinstance(value, bytes):
        return {"_bytes": base64.b64encode(value).decode("ascii")}
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return {"_date": value.isoformat(), "type": type(value).__name__}
    if isinstance(value, Decimal):
        return {"_decimal": str(value)}
    return value


def decode(value):
    if isinstance(value, dict) and "_bytes" in value:
        return base64.b64decode(value["_bytes"])
    if isinstance(value, dict) and "_decimal" in value:
        return Decimal(value["_decimal"])
    if isinstance(value, dict) and "_date" in value:
        kind = {"datetime": datetime.datetime, "date": datetime.date,
                "time": datetime.time}[value["type"]]
        return kind.fromisoformat(value["_date"])
    return value


def clone(settings: dict, target: str, backup_dir: Path) -> dict:
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]{2,63}", target):
        raise ValueError("target must be a simple MySQL schema identifier")
    if target == settings["database"]:
        raise ValueError("target must differ from source")
    backup_dir = backup_dir.resolve()
    if backup_dir.is_relative_to(Path.cwd().resolve()):
        raise ValueError("backup directory must be outside the Git worktree")
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / f"{settings['database']}-before-{target}.json"
    if backup.exists():
        raise FileExistsError(backup)
    connection = mysql.connector.connect(**settings, connection_timeout=5)
    cursor = connection.cursor()
    cursor.execute("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s",
                   (target,))
    if cursor.fetchone():
        connection.close()
        raise ValueError("target schema already exists")
    cursor.execute("SHOW FULL TABLES")
    found = cursor.fetchall()
    if any(row[1] != "BASE TABLE" for row in found):
        raise RuntimeError("views require a separate backup procedure")
    tables = sorted(row[0] for row in found)
    connection.start_transaction(consistent_snapshot=True, readonly=True)
    document = {"source": settings["database"], "tables": {}}
    for table in tables:
        cursor.execute(f"SHOW CREATE TABLE `{table}`")
        create_sql = cursor.fetchone()[1]
        cursor.execute(f"SELECT * FROM `{table}`")
        columns = [column[0] for column in cursor.description]
        rows = [[encode(value) for value in row] for row in cursor.fetchall()]
        document["tables"][table] = {"createSql": create_sql,
                                      "columns": columns, "rows": rows}
    connection.rollback()
    with backup.open("x", encoding="utf-8") as output:
        json.dump(document, output, ensure_ascii=False)
    saved = backup.read_bytes()
    restored = json.loads(saved)
    before = {table: len(data["rows"]) for table, data in restored["tables"].items()}
    if len(before) != len(tables) or not all(data["createSql"].startswith("CREATE TABLE")
                                              for data in restored["tables"].values()):
        connection.close()
        raise RuntimeError("backup schema verification failed")
    # The backup is complete before any target write occurs.
    cursor.execute(f"CREATE DATABASE `{target}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    connection.commit()
    cursor.execute(f"USE `{target}`")
    cursor.execute("SET FOREIGN_KEY_CHECKS=0")
    for table, data in restored["tables"].items():
        cursor.execute(data["createSql"])
    for table, data in restored["tables"].items():
        columns = ",".join(f"`{name}`" for name in data["columns"])
        markers = ",".join("%s" for _ in data["columns"])
        statement = f"INSERT INTO `{table}` ({columns}) VALUES ({markers})"
        rows = [tuple(decode(value) for value in row) for row in data["rows"]]
        if rows:
            cursor.executemany(statement, rows)
    connection.commit()
    cursor.execute("SET FOREIGN_KEY_CHECKS=1")
    for table, count in before.items():
        cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
        if cursor.fetchone()[0] != count:
            raise RuntimeError(f"cloned row count differs for {table}")
    connection.close()
    return {"source": settings["database"], "target": target,
            "backup": str(backup), "backupBytes": backup.stat().st_size,
            "backupSha256": hashlib.sha256(saved).hexdigest(),
            "verifiedCounts": {key: before[key] for key in
                               ("kg_index_task", "kg_entity", "kg_relation", "kg_doc_entity")}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--backup-dir", required=True, type=Path)
    parser.add_argument("--config-dir", type=Path,
                        default=Path("src/main/resources"))
    args = parser.parse_args()
    print(clone(connection_settings(args.config_dir), args.target, args.backup_dir))


if __name__ == "__main__":
    main()
