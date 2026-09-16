"""Optional real PostGIS check. Dedicated random schema, never an existing app schema."""
import json
import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session
from app.models import Area, Dataset
from app.gis import import_zip

@pytest.mark.skipif(not os.getenv("POSTGIS_TEST_DATABASE_URL"),reason="PostgreSQL/PostGIS non disponibile: impostare POSTGIS_TEST_DATABASE_URL sul server di test")
def test_real_migration_geometry_and_import(tmp_path):
    from make_demo import generate
    url=make_url(os.environ["POSTGIS_TEST_DATABASE_URL"])
    admin=create_engine(url)
    with admin.connect() as connection:
        if connection.scalar(text("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('users','companies','datasets','inspections','area_grants')")):
            pytest.skip("Richiesto database di test senza tabelle applicative preesistenti")
    schema="pilot_test_"+uuid4().hex
    with admin.begin() as connection:connection.execute(text(f'CREATE SCHEMA "{schema}"'))
    scoped=url.update_query_dict({"options":f"-csearch_path={schema},public"})
    engine=create_engine(scoped)
    try:
        env={**os.environ,"DATABASE_URL":scoped.render_as_string(hide_password=False),"DB_SCHEMA":schema}
        subprocess.run([sys.executable,"-m","alembic","upgrade","head"],cwd=Path(__file__).resolve().parents[1],env=env,check=True)
        source,config=generate(tmp_path/"demo")
        with Session(engine) as db:
            db.add(Area(id="DEMO",name="SINTETICO PostGIS",synthetic=True));db.commit()
            result=import_zip(db,source,config,tmp_path/"imports")
            assert db.get(Dataset,result["id"]) is not None
            assert db.scalar(text("SELECT count(*) FROM reference_geometries"))==31
            assert db.scalar(text("SELECT min(ST_SRID(geom)) FROM reference_geometries"))==4326
            assert db.scalar(text("SELECT bool_and(ST_IsValid(geom)) FROM reference_geometries")) is True
    finally:
        engine.dispose()
        # Only the generated test schema is removed, never user tables or the database.
        assert schema.startswith("pilot_test_") and len(schema)==43
        with admin.begin() as connection:connection.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        admin.dispose()
