import json
from pathlib import Path
from conftest import operation


def test_v011_events_accepted_by_sync(setup):
    payload = operation(setup)
    payload["inspection"]["events"][0]["app_version"] = "0.11"
    response = setup["client"].post("/api/sync", headers=setup["headers"], json=payload)
    assert response.status_code == 200, response.text


def test_trento_source_matches_android_asset():
    root = Path(__file__).resolve().parents[2]
    data = json.loads((root / "demo/trento-v0.11.json").read_text(encoding="utf-8"))
    asset = json.loads((root / "android/app/src/demo/assets/demo-package.json").read_text(encoding="utf-8"))
    assert data == asset
    assert data["synthetic"]
    assert [point["code"] for point in data["points"]] == [f"PZ-{i:03}" for i in range(1, 11)]
    assert len({point["id"] for point in data["points"]}) == 10
    assert data["points"][-1]["chainage_m"] > 1000
    assert all(point["tag_associations"] == [] for point in data["points"])
