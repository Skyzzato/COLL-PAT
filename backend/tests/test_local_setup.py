import pytest
from supabase_local import read_config


def test_local_config_does_not_execute_shell_or_import_unrelated_variables(tmp_path):
    path = tmp_path / ".env"
    path.write_text("# config\nDATABASE_URL=postgresql+psycopg://example\nDB_SCHEMA=collettori\nOFFLINE_HOURS=72\nPATH=do-not-import\n", encoding="utf-8")
    assert read_config(path) == {"DATABASE_URL": "postgresql+psycopg://example", "DB_SCHEMA": "collettori", "OFFLINE_HOURS": "72"}


@pytest.mark.parametrize("contents", ["DATABASE_URL=sqlite:///local.db\nDB_SCHEMA=collettori", "DATABASE_URL=postgresql+psycopg://example\nDB_SCHEMA=public"])
def test_local_config_rejects_wrong_database_or_schema(tmp_path, contents):
    path = tmp_path / ".env"
    path.write_text(contents, encoding="utf-8")
    with pytest.raises(ValueError): read_config(path)
