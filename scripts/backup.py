"""Binary-safe Docker Compose backup. Run from repository root.

The dump includes personal data and password hashes: store it encrypted with restricted access.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

def run(args,output):
    with output.open("xb") as stream: subprocess.run(args,stdout=stream,check=True)

def main():
    p=argparse.ArgumentParser();p.add_argument("destination");args=p.parse_args()
    target=Path(args.destination);target.mkdir(parents=True,exist_ok=False)
    run(["docker","compose","exec","-T","db","pg_dump","-U","collettori","-d","collettori","-Fc"],target/"database.dump")
    run(["docker","compose","exec","-T","api","tar","-czf","-","-C","/srv","runtime"],target/"runtime.tar.gz")
    manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in target.iterdir()}
    (target/"sha256.json").write_text(json.dumps(manifest,indent=2),encoding="utf-8")
    print("Backup completato. Verificare il ripristino in un ambiente isolato:",target)
if __name__=="__main__":main()
