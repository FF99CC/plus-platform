"""Container-only initialization: render upstream seed SQL for an empty MySQL volume."""
import os
import re
from pathlib import Path


ACCOUNTS = {"admin": "ADMIN_PASSWORD", "test": "TEST_PASSWORD", "test1": "TEST1_PASSWORD"}
SQL_CHARSET = "SET NAMES utf8mb4;\n"


def render_password_sql(hashes):
    if set(hashes) != set(ACCOUNTS):
        raise ValueError("Initialization requires exactly the three upstream demo accounts.")
    lines = ["-- Password hashes for this independent local installation.", "START TRANSACTION;"]
    for name in ACCOUNTS:
        hashed = hashes[name]
        if not re.fullmatch(r"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}", hashed):
            raise ValueError("Invalid bcrypt hash.")
        lines.append("UPDATE sys_user SET password = '%s' WHERE user_name = '%s';" % (hashed, name))
    # Upstream nicknames contain a hard-coded demo password that is no longer valid.
    lines.extend([
        "UPDATE sys_user SET nick_name = '本部门及以下测试账号' WHERE user_name = 'test';",
        "UPDATE sys_user SET nick_name = '仅本人测试账号' WHERE user_name = 'test1';",
        "COMMIT;",
    ])
    return "\n".join(lines) + "\n"


def write_seed(source, target, hashes):
    password_sql = render_password_sql(hashes)
    target.mkdir(parents=True, exist_ok=True)
    # A completed bootstrap is required before MySQL can start; no concurrent reading.
    for original, destination in (
        ("ry_vue.sql", "010-base.sql"), ("ry_workflow.sql", "020-workflow.sql")
    ):
        (target / destination).write_text(
            SQL_CHARSET + (source / original).read_text(encoding="utf-8"), encoding="utf-8"
        )
    (target / "090-credentials.sql").write_text(SQL_CHARSET + password_sql, encoding="utf-8")
    (target / "099-complete.sql").write_text(
        SQL_CHARSET + "CREATE TABLE local_bootstrap_complete (id INT PRIMARY KEY, "
        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);\n"
        "INSERT INTO local_bootstrap_complete (id) VALUES (1);\n", encoding="utf-8"
    )
    # Official MySQL entrypoint drops to the mysql user before reading SQL.
    target.chmod(0o755)
    for path in target.glob("*.sql"):
        path.chmod(0o644)


def main():
    import bcrypt  # Installed only in the bootstrap image; no host pip installation.

    hashes = {}
    for account, key in ACCOUNTS.items():
        value = os.environ[key]
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,20}", value):
            raise ValueError("Invalid local account password configuration: " + key)
        # Prefix 2a matches the Java application's Hutool BCrypt implementation.
        hashes[account] = bcrypt.hashpw(value.encode(), bcrypt.gensalt(rounds=12, prefix=b"2a")).decode()
    write_seed(Path("/seed"), Path("/init-sql"), hashes)
    print("Initial SQL prepared. Existing MySQL volumes will not be reinitialized.")


if __name__ == "__main__":
    main()
