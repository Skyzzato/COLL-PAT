"""Receive a password via private child-process stdin, never command arguments."""
import getpass
import json
import os
import sys
from supabase_local import ROOT, read_config


def main():
    try:
        data = json.load(sys.stdin)
        password = data["password"]
        username = data["username"]
        if not isinstance(password, str) or len(password) < 12:
            raise ValueError("Password troppo breve")
        if not isinstance(username, str) or not username.strip():
            raise ValueError("Username mancante")
        os.environ.update(read_config())
        os.chdir(ROOT / "backend")
        sys.path.insert(0, str(ROOT / "backend"))
        from app.cli import main as bootstrap
        # Reuse the same transactional, duplicate-safe administrative bootstrap.
        getpass.getpass = lambda prompt="": password
        sys.argv = ["bootstrap", "bootstrap", "--username", username,
                    "--company", "Impresa pilota sintetica", "--area", "DEMO",
                    "--area-name", "Area dimostrativa sintetica", "--synthetic"]
        bootstrap()
    except BaseException:
        print("Creazione non riuscita: verificare connessione o account gia esistente.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
