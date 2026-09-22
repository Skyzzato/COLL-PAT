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


def test_trento_source_matches_android_asset():
    root = Path(__file__).resolve().parents[2]
    data = json.loads((root / "demo/trento-lavis-gilli-v0.14.json").read_text(encoding="utf-8"))
    asset = json.loads((root / "android/app/src/demo/assets/demo-package.json").read_text(encoding="utf-8"))
    assert data == asset
    assert data["synthetic"]
    assert [point["code"] for point in data["points"][:10]] == [f"PZ-{i:03}" for i in range(1, 11)]
    assert len({point["id"] for point in data["points"]}) == 22
    assert data["points"][9]["chainage_m"] > 1000
    assert all(point.get("tag_associations", []) == [] for point in data["points"])
    historical = json.loads((root / "demo/trento-v0.11.json").read_text(encoding="utf-8"))
    assert [p['id'] for p in historical['points']] == [p['id'] for p in data['points'][:10]]
