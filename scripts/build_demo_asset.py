"""Copy the versioned synthetic dataset; no database or real infrastructure."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
payload=json.loads((ROOT/'demo/trento-v0.11.json').read_text(encoding='utf-8'))
assert payload['synthetic'] and len(payload['points'])==10
(ROOT/'android/app/src/demo/assets/demo-package.json').write_text(json.dumps(payload,ensure_ascii=False),encoding='utf-8')
print('Demo Trento: 10 pozzetti, 9 segmenti sintetici')
