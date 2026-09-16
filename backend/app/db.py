import os
import re
from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker

URL = os.getenv("DATABASE_URL", "sqlite:///./runtime/pilot.db")
DB_SCHEMA = os.getenv("DB_SCHEMA", "public")
if not re.fullmatch(r"[a-z_][a-z0-9_]*", DB_SCHEMA):
    raise ValueError("DB_SCHEMA deve essere un identificatore PostgreSQL semplice")
engine = create_engine(URL, pool_pre_ping=True, **({"connect_args": {"check_same_thread": False}} if URL.startswith("sqlite") else {}))

def configure_postgres(connection, _):
    # Direct or session-pooler connections only. Preserve this setting across rollback.
    previous = connection.autocommit
    connection.autocommit = True
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT n.nspname FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='postgis'")
            extension = cursor.fetchone()
            schemas = [DB_SCHEMA]
            if extension and extension[0] not in schemas:
                schemas.append(extension[0])
            path = ",".join('"' + name.replace('"', '""') + '"' for name in schemas)
            cursor.execute("SELECT pg_catalog.set_config('search_path', %s, false)", (path,))
    finally:
        connection.autocommit = previous

if engine.dialect.name == "postgresql":
    event.listen(engine, "connect", configure_postgres)
if URL.startswith("sqlite"):
    @event.listens_for(engine, "connect")
    def sqlite_fk(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA journal_mode=WAL")

Session = sessionmaker(engine, expire_on_commit=False)

class Base(DeclarativeBase):
    pass

def session():
    with Session() as db:
        yield db
