# Upstox setup

Marksy uses three Upstox tokens, each for one job:

- **Analytics Token** (about a year, read-only): market data. It goes in the QR.
- **Daily access token** (until about 3:30 am IST): holdings, positions, funds and today's orders, read-only. It comes only from the in-app Upstox sign-in, never from a QR.
- **Sandbox token** (30 days): practice orders on Upstox's sandbox only. Nothing reaches your account.

Live orders are not available: since 1 April 2026 Upstox accepts order calls only from a registered static IP, and a phone has none.

## 1. The live app

Upstox allows one active API app per person and deletes older ones when you make another. Use the app you already have; it is the only live app Marksy uses.

In Upstox Developer Apps, open your app and set the redirect URL to `http://127.0.0.1/marksy-upstox`. Copy the API key and API secret.

## 2. Analytics Token

Developer Apps › Analytics Token › Generate. It is read-only and lasts a year.

## 3. Sandbox token (wait)

Upstox does not say whether a sandbox app counts toward the one-app rule. Until Upstox confirms in writing that creating one cannot delete or invalidate your live app, its keys or tokens, do not create it. The Buy/Sell ticket's Preview (next update) won't need it.

Once confirmed: Developer Apps › Sandbox › New Sandbox App › Generate.

## 4. Make the QR on your computer

    pip install segno
    python tools/make_upstox_qr.py

It asks for each secret without showing it (Enter leaves a field out; the redirect URL is shown as you type) and saves `upstox-qr.png` in your home folder, outside the repo. On Windows use PowerShell or Windows Terminal; in Git Bash run `winpty python tools/make_upstox_qr.py`.

The QR is **not encrypted**. Anyone with the image can read the keys. Never use an online QR site, keep the image out of cloud photo backups, and delete it after importing.

## 5. Import it

Marksy › Settings › Upstox › Import from QR (camera or a saved image). The code only fills the form; check the fields, then tap **Save & test**. Delete the PNG. Prefer scanning the code on your computer screen. If you copied the image to the phone, delete it there too, including the Photos trash and any cloud backup.

## Format (version 1)

    {
      "type": "marksy-upstox",
      "v": 1,
      "api_key": "…",
      "api_secret": "…",
      "redirect_uri": "http://127.0.0.1/marksy-upstox",
      "analytics_token": "…",
      "sandbox_token": "…"
    }

- `type` and `v` are required; every other field is optional. A field left out keeps the saved value.
- Refused: invalid JSON, a repeated field, any other field (including `access_token`), a non-text or empty value, values with spaces, a code over 4 KB, `v` other than 1, and a redirect URL that is not `https://`, or `http://` to `127.0.0.1`, `localhost` or `[::1]`, or that contains a backslash, `@` or a `#fragment`.
- The same token in both token fields is refused.
