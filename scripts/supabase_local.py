"""Interactive local setup; secrets never appear in arguments or command output."""
import argparse
import getpass
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"


def read_config(path=ENV_FILE):
    values = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        key, separator, value = raw.partition("=")
        if separator and key.strip() in {"DATABASE_URL", "DB_SCHEMA", "OFFLINE_HOURS"}:
            values[key.strip()] = value.strip()
    if not values.get("DATABASE_URL", "").startswith("postgresql+psycopg://"):
        raise ValueError("Configurazione PostgreSQL mancante")
    if values.get("DB_SCHEMA") != "collettori":
        raise ValueError("Schema collettori richiesto")
    return values


def main():
    parser = argparse.ArgumentParser(description="Collettori: collegamento locale a Supabase")
    parser.add_argument("action", choices=["configure", "check", "serve", "admin"])
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action == "configure":
        if ENV_FILE.exists():
            raise SystemExit(".env esiste già: nessuna sovrascrittura. Usare check o modificare privatamente il file.")
        print("Usare la password del ruolo collettori_backend appena impostata nel SQL Editor.")
        password = getpass.getpass("Password database (nascosta): ")
        if len(password) < 20:
            raise SystemExit("Usare una password casuale di almeno 20 caratteri.")
        if password != getpass.getpass("Ripetere password: "):
            raise SystemExit("Le password non coincidono; nessun file creato.")
        url = ("postgresql+psycopg://collettori_backend.zzipvrnhndigepufhkcj:"
               + quote(password, safe="")
               + "@aws-1-eu-west-1.pooler.supabase.com:5432/postgres?sslmode=require&connect_timeout=10")
        with ENV_FILE.open("x", encoding="utf-8") as target:
            target.write("DATABASE_URL=" + url + "\nDB_SCHEMA=collettori\nOFFLINE_HOURS=72\n")
        if os.name != "nt": ENV_FILE.chmod(0o600)
        print("Configurazione salvata nel .env locale escluso da Git. Ora eseguire check.")
        return
    try:
        values = read_config()
    except (OSError, ValueError):
        raise SystemExit("Configurazione locale assente o non valida. Eseguire configure.")
    os.environ.update(values)
    os.chdir(ROOT / "backend")
    sys.path.insert(0, str(ROOT / "backend"))
    if args.action == "check":
        from sqlalchemy import text
        from app.db import engine
        try:
            with engine.connect() as connection:
                if connection.scalar(text("SELECT current_user")) != "collettori_backend":
                    raise ValueError("Ruolo inatteso")
                if connection.scalar(text("SELECT current_schema()")) != "collettori":
                    raise ValueError("Schema inatteso")
                if connection.scalar(text("SELECT version_num FROM alembic_version")) != "0001":
                    raise ValueError("Versione inattesa")
                connection.execute(text("SELECT ST_SRID(ST_SetSRID(ST_MakePoint(11,46),4326))")).scalar_one()
                users = connection.scalar(text("SELECT count(*) FROM users"))
                datasets = connection.scalar(text("SELECT count(*) FROM datasets"))
            print(f"Connessione OK: ruolo backend, schema collettori, versione 0001, PostGIS. Account: {users}; dataset: {datasets}.")
        except Exception:
            raise SystemExit("Verifica fallita. Controllare password del ruolo, disponibilità del progetto, migrazione e rete. Nessuna credenziale stampata.")
        finally:
            engine.dispose()
    elif args.action == "serve":
        (ROOT / "backend/runtime").mkdir(exist_ok=True)
        import uvicorn
        print("Portale locale: http://127.0.0.1:8000 — arrestare con Ctrl+C")
        uvicorn.run("app.main:app", host="127.0.0.1", port=8000)
    else:
        raise SystemExit(subprocess.call([sys.executable, "-m", "app.cli", *args.arguments], env=os.environ.copy()))


if __name__ == "__main__":
    main()
