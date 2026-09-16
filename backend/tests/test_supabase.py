from pathlib import Path
import pytest
from app.db import configure_postgres
from generate_supabase_sql import render, OUTPUT


class Connection:
    def __init__(self, extension, fail=False):
        self.autocommit = False
        self.extension = extension
        self.fail = fail
        self.calls = []
    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, query, args=None):
        assert self.autocommit
        self.calls.append((query, args))
        if self.fail: raise RuntimeError("connection failed")
    def fetchone(self): return (self.extension,) if self.extension else None


def test_sql_matches_current_model():
    assert OUTPUT.read_text(encoding="utf-8") == render()


@pytest.mark.parametrize("extension", ["extensions", "gis", "public", None, 'odd"schema'])
def test_private_schema_and_postgis_search_path(monkeypatch, extension):
    monkeypatch.setattr("app.db.DB_SCHEMA", "collettori")
    connection = Connection(extension)
    configure_postgres(connection, None)
    expected = '"collettori"'
    if extension: expected += ',"' + extension.replace('"', '""') + '"'
    assert connection.calls[-1][1] == (expected,)
    assert connection.autocommit is False


def test_connection_state_restored_after_failure():
    connection = Connection("extensions", fail=True)
    with pytest.raises(RuntimeError): configure_postgres(connection, None)
    assert connection.autocommit is False
