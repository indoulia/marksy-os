#!/usr/bin/env python3
"""Make a Marksy Upstox QR code (format v1) on this computer, offline.

The QR is NOT encrypted: anyone with the image can read the keys. Treat the PNG like a
password file, never use an online QR site, and delete it after importing it in Marksy.

    pip install segno
    python tools/make_upstox_qr.py [--out PATH] [--force]   # default: upstox-qr.png in your home folder
    python tools/make_upstox_qr.py --sample --out sample-qr.png     # fake values, to test the import

On Windows run it in PowerShell or Windows Terminal (in Git Bash use: winpty python ...),
so the hidden prompts work.
"""
import argparse
import getpass
import json
import os
import re
import sys
from urllib.parse import urlsplit

TYPE = "marksy-upstox"
VERSION = 1
MAX_BYTES = 4096
DEFAULT_REDIRECT = "http://127.0.0.1/marksy-upstox"
FIELDS = [  # key, prompt, hidden, max length
    ("api_key", "API key", True, 128),
    ("api_secret", "API secret", True, 128),
    ("redirect_uri", f"Redirect URL (Marksy's default is {DEFAULT_REDIRECT})", False, 512),
    ("analytics_token", "Analytics Token", True, 2048),
    ("sandbox_token", "Sandbox token", True, 2048),
]
PRINTABLE = re.compile(r"[\x21-\x7e]+")
LOOPBACK = {"127.0.0.1", "localhost", "::1"}
SAMPLE = {
    "api_key": "sample-api-key",
    "api_secret": "sample-api-secret",
    "redirect_uri": DEFAULT_REDIRECT,
    "analytics_token": "sample.analytics.token",
    "sandbox_token": "sample.sandbox.token",
}


def problem(key, label, value, limit):
    if not PRINTABLE.fullmatch(value):
        return f"{label} has spaces or characters Marksy can't use."
    if len(value) > limit:
        return f"{label} is too long."
    if key == "redirect_uri":
        if "\\" in value:
            return "The redirect URL can't contain a backslash."
        url = urlsplit(value)
        if not (url.scheme == "https" or (url.scheme == "http" and url.hostname in LOOPBACK)):
            return "The redirect URL must be https, or http to 127.0.0.1 on the phone."
        if "@" in value or url.fragment:
            return "The redirect URL can't have a user name or a #fragment."
    return None


def ask():
    fields = {}
    for key, label, hidden, limit in FIELDS:
        while True:
            prompt = f"{label} (Enter to leave out): "
            value = (getpass.getpass(prompt) if hidden else input(prompt)).strip()
            if not value:
                break
            issue = problem(key, label, value, limit)
            if issue is None:
                fields[key] = value
                break
            print(issue, file=sys.stderr)
    return fields


def main():
    parser = argparse.ArgumentParser(description="Make a Marksy Upstox QR code offline.")
    parser.add_argument("--out", default=os.path.join(os.path.expanduser("~"), "upstox-qr.png"), help="PNG to write (default: upstox-qr.png in your home folder)")
    parser.add_argument("--force", action="store_true", help="overwrite an existing file")
    parser.add_argument("--sample", action="store_true", help="use fake values, to test the import")
    args = parser.parse_args()
    try:
        import segno
    except ImportError:
        sys.exit("Needs segno: pip install segno")
    if os.path.exists(args.out) and not args.force:
        sys.exit(f"{args.out} already exists; pass --force to overwrite it.")
    fields = dict(SAMPLE) if args.sample else ask()
    if not fields:
        sys.exit("Nothing to put in the QR.")
    if fields.get("analytics_token") and fields.get("analytics_token") == fields.get("sandbox_token"):
        sys.exit("The same token is in both token fields.")
    text = json.dumps({"type": TYPE, "v": VERSION, **fields}, separators=(",", ":"))
    if len(text.encode("utf-8")) > MAX_BYTES:
        sys.exit("The code would be over 4 KB.")
    try:
        code = segno.make(text, error="m", micro=False)
    except segno.DataOverflowError:
        sys.exit("Too much for one QR code. Leave out a field, import this code, then make a second one for the rest.")
    code.save(args.out, scale=6, border=4)
    if os.name == "posix":
        os.chmod(args.out, 0o600)
    print(f"Saved {args.out} with: {', '.join(fields)}.")
    print("It is not encrypted. Import it in Marksy, then delete it.")


if __name__ == "__main__":
    main()
