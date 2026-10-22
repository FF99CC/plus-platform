"""Versioned local MySQL migrations. Never reset databases or replay applied files."""
import hashlib
import re
import subprocess
from pathlib import Path
from .local_config import ConfigError, compose_invocation


def sql(root, config, statement):
    # The password is expanded only inside our db container, not in process arguments.
    command, env = compose_invocation(root, config, [
        'exec', '-T', 'db', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 '
        '--batch --raw --skip-column-names -uroot plus_platform'])
    result = subprocess.run(command, env=env, cwd=str(root), input=statement,
                            text=True, capture_output=True)
    if result.returncode:
        raise ConfigError('本工程数据库操作失败：' + result.stderr[-2000:])
    return result.stdout.strip()


def pending_migrations(directory, applied):
    result=[]
    for path in sorted(Path(directory).glob('V*.sql')):
        if not re.fullmatch(r'V[0-9]{3}__[a-z_]+\.sql', path.name):
            raise ConfigError('迁移文件名无效：' + path.name)
        checksum=hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in applied:
            original, success=applied[path.name]
            if original != checksum:
                raise ConfigError('已执行迁移被修改，请新增迁移而不要改历史文件：' + path.name)
            if success != '1':
                raise ConfigError('迁移曾中断，需要核对实际数据库后恢复；不会自动重置或重跑：' + path.name)
        else:
            result.append((path,checksum))
    missing=set(applied)-{p.name for p in Path(directory).glob('V*.sql')}
    if missing:
        raise ConfigError('本地缺少已执行的迁移文件：' + ', '.join(sorted(missing)))
    return result


def migrate(root, config):
    # CLI entry points validate the Docker endpoint and Compose before calling.
    sql(root,config,'''CREATE TABLE IF NOT EXISTS local_schema_history (
        version VARCHAR(120) PRIMARY KEY, checksum CHAR(64) NOT NULL,
        success BOOLEAN NOT NULL DEFAULT FALSE, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB;''')
    rows=sql(root,config,'SELECT version,checksum,success FROM local_schema_history;')
    applied={parts[0]:(parts[1],parts[2]) for parts in (line.split('\t') for line in rows.splitlines())}
    pending=pending_migrations(Path(root)/'ops/migrations',applied)
    for path,checksum in pending:
        # Host operation_lock serializes this command. A dirty marker survives MySQL DDL autocommit.
        sql(root,config,"INSERT INTO local_schema_history(version,checksum) VALUES ('%s','%s');" % (path.name,checksum))
        statement='SET NAMES utf8mb4;\n'+path.read_text(encoding='utf-8')
        sql(root,config,statement)
        sql(root,config,"UPDATE local_schema_history SET success=TRUE WHERE version='%s';" % path.name)
        print('已执行迁移：'+path.name)
    if not pending: print('数据库迁移已是最新，没有重复执行。')
