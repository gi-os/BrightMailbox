#!/usr/bin/env python3
"""
Sign in on a computer, hand the phone a refresh token by QR.

The fallback for when LightOS's browser opens the consent page but eats the redirect
back. Same idiom the other Light Phone apps use for API keys: the credential arrives
through the camera, not the keyboard.

    python3 authorize.py google    --client-id … [--client-secret …]
    python3 authorize.py microsoft --client-id …

Google needs a **Desktop**-type client here, not the Android one — Google will not
accept a loopback redirect for an Android client. Desktop clients have a "secret", which
Google expects to ship inside installed apps and is not confidential; the app stores it
and sends it on refresh.

Microsoft needs http://localhost added as a redirect URI on the app registration, under
"Mobile and desktop applications".
"""

import argparse
import base64
import hashlib
import http.server
import json
import os
import secrets
import threading
import urllib.parse
import urllib.request
import webbrowser

ENDPOINTS = {
    "google": (
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "openid email https://www.googleapis.com/auth/gmail.modify",
    ),
    "microsoft": (
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "openid email offline_access Mail.ReadWrite Mail.Send User.Read",
    ),
}

code_holder = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        code_holder.update({k: v[0] for k, v in q.items()})
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write("Done. Close this tab and look at the terminal.".encode())

    def log_message(self, *a):
        pass


def b64(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def qr(text):
    """Print a QR to the terminal, if qrcode is installed."""
    try:
        import qrcode
    except ImportError:
        print("\n(pip install qrcode to get a scannable code here)\n")
        print(text)
        return
    q = qrcode.QRCode(border=1)
    q.add_data(text)
    q.make(fit=True)
    q.print_ascii(invert=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("service", choices=list(ENDPOINTS))
    p.add_argument("--client-id", required=True)
    p.add_argument("--client-secret", default="")
    p.add_argument("--port", type=int, default=8731)
    a = p.parse_args()

    auth_url, token_url, scopes = ENDPOINTS[a.service]
    redirect = f"http://localhost:{a.port}"

    verifier = b64(os.urandom(64))
    challenge = b64(hashlib.sha256(verifier.encode("ascii")).digest())
    state = secrets.token_urlsafe(12)

    params = {
        "client_id": a.client_id,
        "redirect_uri": redirect,
        "response_type": "code",
        "scope": scopes,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
    }
    if a.service == "google":
        # Google issues a refresh token only on the first grant, so force consent or a
        # re-run comes back with an access token and no way to renew it.
        params["access_type"] = "offline"
        params["prompt"] = "consent"

    server = http.server.HTTPServer(("localhost", a.port), Handler)
    threading.Thread(target=server.handle_request, daemon=True).start()

    url = auth_url + "?" + urllib.parse.urlencode(params)
    print("Opening:\n " + url + "\n")
    webbrowser.open(url)
    while "code" not in code_holder and "error" not in code_holder:
        pass
    server.server_close()

    if "error" in code_holder:
        raise SystemExit("consent failed: " + code_holder.get("error", "?"))
    if code_holder.get("state") != state:
        raise SystemExit("state mismatch — abandoning")

    form = {
        "grant_type": "authorization_code",
        "code": code_holder["code"],
        "client_id": a.client_id,
        "redirect_uri": redirect,
        "code_verifier": verifier,
    }
    if a.client_secret:
        form["client_secret"] = a.client_secret
    if a.service == "microsoft":
        form["scope"] = scopes

    req = urllib.request.Request(
        token_url,
        data=urllib.parse.urlencode(form).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    body = json.loads(urllib.request.urlopen(req).read())

    refresh = body.get("refresh_token")
    if not refresh:
        raise SystemExit("no refresh_token came back — check the scopes")

    email = ""
    if body.get("id_token"):
        payload = body["id_token"].split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
        email = claims.get("email") or claims.get("preferred_username") or ""

    payload = json.dumps(
        {
            "service": a.service,
            "client_id": a.client_id,
            "client_secret": a.client_secret,
            "refresh_token": refresh,
            "email": email,
        },
        separators=(",", ":"),
    )
    print(f"\nSigned in as {email or '(unknown)'}. Scan this in Mailbox:\n")
    qr(payload)


if __name__ == "__main__":
    main()
