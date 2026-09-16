"""Restore into an EMPTY, isolated Docker Compose database; never silently overwrites data."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument("backup");args=p.parse_args();source=Path(args.backup)
    for name,digest in json.loads((source/"sha256.json").read_text()).items():
        if name not in ["database.dump","runtime.tar.gz"]:raise SystemExit("Manifest non valido")
        if hashlib.sha256((source/name).read_bytes()).hexdigest()!=digest:raise SystemExit("Backup non integro")
    r=subprocess.run(["docker","compose","exec","-T","db","psql","-U","collettori","-d","collettori","-Atc","SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='users'"],capture_output=True,text=True,check=True)
    if r.stdout.strip()!="0":raise SystemExit("Database non vuoto: usare un ambiente di ripristino separato")
    with (source/"database.dump").open("rb") as data:subprocess.run(["docker","compose","exec","-T","db","pg_restore","-U","collettori","-d","collettori","--no-owner","--exit-on-error"],stdin=data,check=True)
    with (source/"runtime.tar.gz").open("rb") as data:subprocess.run(["docker","compose","run","--rm","--no-deps","-T","api","tar","-xzf","-","-C","/srv"],stdin=data,check=True)
    print("Ripristino eseguito. Avviare API e verificare conteggi, storico, report e accessi.")
if __name__=="__main__":main()
