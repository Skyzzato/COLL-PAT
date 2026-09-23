import json
import pytest
from pathlib import Path
from conftest import operation


@pytest.mark.parametrize("version", ["0.11", "0.12"])
def test_v011_events_accepted_by_sync(setup, version):
    payload = operation(setup)
    payload["inspection"]["events"][0]["app_version"] = version
    response = setup["client"].post("/api/sync", headers=setup["headers"], json=payload)
    assert response.status_code == 200, response.text


def test_trento_source_retains_historical_ids_in_server_seed():
    root = Path(__file__).resolve().parents[2]
    data = json.loads((root / "demo/trento-lavis-gilli-v0.14.json").read_text(encoding="utf-8"))
    migration = (root / "supabase/migrations/202609230006_coll_pat_v016_server_seed.sql").read_text(encoding="utf-8")
    items = json.loads(migration.split('$json$')[1])
    for plural, kind in [('collectors','collector'),('points','point'),('segments','segment')]:
        seeded = [{k:v for k,v in row['data'].items() if k not in ('server_seed','source_identity')} for row in items if row['kind']==kind]
        assert data[plural] == seeded
    assert not (root / "android/app/src/demo/assets/demo-package.json").exists()
    assert data["synthetic"]
    assert [point["code"] for point in data["points"][:10]] == [f"PZ-{i:03}" for i in range(1, 11)]
    assert len({point["id"] for point in data["points"]}) == 22
    assert data["points"][9]["chainage_m"] > 1000
    assert all(point.get("tag_associations", []) == [] for point in data["points"])
    historical = json.loads((root / "demo/trento-v0.11.json").read_text(encoding="utf-8"))
    assert [p['id'] for p in historical['points']] == [p['id'] for p in data['points'][:10]]
