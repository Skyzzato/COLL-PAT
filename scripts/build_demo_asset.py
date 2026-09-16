"""Create the bundled synthetic Android dataset using an isolated in-memory DB."""
import json
import sys
import tempfile
from pathlib import Path
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.db import Base
from app.models import Area, Dataset
from app.gis import import_zip


def main():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    with tempfile.TemporaryDirectory(prefix="collettori-demo-") as temp, Session(engine) as db:
        db.add(Area(id="DEMO", name="Area dimostrativa sintetica", synthetic=True))
        db.commit()
        import_zip(db, ROOT / "demo/synthetic.zip", json.loads((ROOT / "demo/mapping.json").read_text(encoding="utf-8")), Path(temp))
        payload = db.scalar(select(Dataset)).payload
        payload["version"] = "00000000-0000-4000-8000-000000000002"
        destination = ROOT / "android/app/src/demo/assets/demo-package.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")), encoding="utf-8")
        print(f"Demo inclusa: {len(payload['points'])} pozzetti, {len(payload['segments'])} tratti")
    engine.dispose()


if __name__ == "__main__":
    main()
