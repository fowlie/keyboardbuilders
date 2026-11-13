#!/usr/bin/env python3
"""PUT a file into Hetzner Object Storage, signed with AWS SigV4, standard library only.

Backlog #60. This exists instead of `boto3` because every script in `scripts/` uses the
standard library and nothing else, and the alternative was ~80 MB of botocore, urllib3,
s3transfer and jmespath to issue one HTTP verb. Nothing S3 was installed on this machine
to begin with -- `boto3`, `botocore`, `minio`, `aws`, `mc`, `rclone`, `s5cmd` and `s3cmd`
are all absent -- so every option cost a new dependency, and this one costs the least.

Hetzner's own getting-started endorses the same primitive rather than an SDK: it signs
with `curl --aws-sigv4 "aws:amz:${REGION}:s3"`. The region string is literally `hel1`.

THE SIGNER IS VERIFIED AGAINST CURL, WHICH IS THE ONLY REASON HAND-ROLLING IT IS
DEFENSIBLE. `selftest()` below makes curl sign a real request, reads back the
`Authorization` and `x-amz-date` curl actually sent, recomputes the signature here from
that same date, and compares. A signer that is wrong by one byte fails every upload with
an opaque 403, which is a bad afternoon; a signer with a live oracle test is arithmetic.
Run it after touching anything in `sign()`.

`x-amz-content-sha256` CARRIES THE REAL PAYLOAD DIGEST, and that is worth recording
because the research that preceded this file asserted the opposite -- that curl signs
`UNSIGNED-PAYLOAD` over HTTPS -- and the oracle test refuted it on the first run:

    curl 8.21.0 sent  x-amz-content-sha256: d354caf95d3e6516b85e558edc25f44a260802e9a2daa18a01fc1cf8459673da
    sha256("selftest")                      d354caf95d3e6516b85e558edc25f44a260802e9a2daa18a01fc1cf8459673da

So the digest goes in the header AND as the canonical request's final line, both. The
signer that assumed otherwise produced a plausible signature that would have failed every
upload with an opaque 403. This is exactly why the oracle exists rather than a fixed
vector copied from someone's summary.

Usage:
    objectstorage.py --selftest                    verify the signer against curl
    objectstorage.py --put FILE --key boards/x.glb --content-type model/gltf-binary
"""
import argparse
import datetime
import hashlib
import hmac
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

CONFIG = pathlib.Path.home()/".config/keyboardbuilders/objectstorage.env"
SIGNED_HEADERS = "content-type;host;x-amz-content-sha256;x-amz-date"

REPO = pathlib.Path(__file__).resolve().parent.parent

# Content types set at upload time, because a browser will not treat a GLB as a model if
# the store serves it as `application/octet-stream`, and model-viewer's fetch is the
# consumer. Keyed by suffix so a caller cannot forget.
CONTENT_TYPES = {".glb": "model/gltf-binary", ".png": "image/png", ".jpg": "image/jpeg"}


def credentials(path=CONFIG):
    """Read the KEY=VALUE credential file, refusing anything careless about it.

    PARSED, NEVER SOURCED. This project has already been bitten once by a shell wrapper
    mangling quoted arguments (see the callbash note in deploy/README.md), and a Python
    parser cannot care which shell invoked it.
    """
    # THE ENVIRONMENT ALONE IS ENOUGH. The intake bot (backlog #104) gets both keys from
    # systemd's EnvironmentFile and has no credential file; requiring one anyway failed
    # every answer poll it ran. A file that does exist is still held to the checks below.
    env_only = not path.is_file() and all(
        os.environ.get(n) for n in ("KB_S3_ACCESS_KEY", "KB_S3_SECRET_KEY"))
    if env_only:
        path = None
    elif not path.is_file():
        sys.exit(f"no credentials at {path} (and KB_S3_ACCESS_KEY/KB_S3_SECRET_KEY are not "
                 f"both set) -- see this module's docstring")
    out = {}
    # A world- or group-readable secret is the one mistake worth refusing outright.
    mode = path.stat().st_mode & 0o077 if path else 0
    if mode:
        sys.exit(f"{path} is mode {oct(path.stat().st_mode & 0o777)}; chmod 600 it")
    # AND IT MUST NOT LIVE IN THE REPO, checked rather than trusted: "never in git
    # history" is worth being a property of the code instead of a convention.
    if path and path.resolve().is_relative_to(REPO):
        sys.exit(f"{path} is inside the repository; move it outside")
    for line in (path.read_text().splitlines() if path else []):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    # THE FILE'S KEYS ARE UNPREFIXED AND THE ENVIRONMENT'S ARE NOT, deliberately. The
    # file already lives in a keyboardbuilders-specific directory, so `ACCESS_KEY` there
    # is unambiguous and `KB_S3_ACCESS_KEY` would be saying it twice. An environment
    # variable has no such directory around it, so it keeps the prefix -- a bare
    # `ACCESS_KEY` exported in a shell is far too generic to read as ours.
    #
    # Bucket and region default rather than being required: there is one bucket, its name
    # is not a secret, and a credential file that must restate it is a file with two more
    # things to get wrong. Either can still be overridden.
    resolved = {}
    for name, file_key, default in (
        ("KB_S3_ACCESS_KEY", "ACCESS_KEY", None),
        ("KB_S3_SECRET_KEY", "SECRET_KEY", None),
        ("KB_S3_BUCKET", "BUCKET", "keyboardbuilders-renders"),
        ("KB_S3_REGION", "REGION", "hel1"),
    ):
        resolved[name] = os.environ.get(name) or out.get(file_key) or default or ""
        if not resolved[name]:
            sys.exit(f"{file_key} is missing from {path} (or {name} from the environment)")
    return resolved


def _hmac(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def sign(secret, region, amzdate, canonical_request):
    """The SigV4 signature for a canonical request. Pure arithmetic, hence testable."""
    datestamp = amzdate[:8]
    scope = f"{datestamp}/{region}/s3/aws4_request"
    to_sign = "\n".join([
        "AWS4-HMAC-SHA256", amzdate, scope,
        hashlib.sha256(canonical_request.encode()).hexdigest(),
    ])
    k = _hmac(f"AWS4{secret}".encode(), datestamp)
    for part in (region, "s3", "aws4_request"):
        k = _hmac(k, part)
    return hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest(), scope


def canonical(method, host, key, content_type, amzdate, payload_sha):
    """The canonical request curl builds for a virtual-hosted-style PUT."""
    return "\n".join([
        method,
        "/" + key.lstrip("/"),
        "",                                    # no query string
        f"content-type:{content_type}",
        f"host:{host}",
        f"x-amz-content-sha256:{payload_sha}",
        f"x-amz-date:{amzdate}",
        "",
        SIGNED_HEADERS,
        payload_sha,
    ])


def authorization(access, secret, region, host, key, content_type, amzdate, payload_sha,
                  method="PUT"):
    signature, scope = sign(secret, region, amzdate,
                            canonical(method, host, key, content_type, amzdate, payload_sha))
    return (f"AWS4-HMAC-SHA256 Credential={access}/{scope}, "
            f"SignedHeaders={SIGNED_HEADERS}, Signature={signature}")


def put(local, key, creds=None):
    """Upload one file. Returns the ETag the store reports.

    The ETag is READ BACK rather than assumed: this project's own rule is that a write
    reporting success is not evidence the write happened, learned the hard way from a
    renderer that logs "Successfully created" and returns 0 having written nothing.
    """
    creds = creds or credentials()
    local = pathlib.Path(local)
    bucket, region = creds["KB_S3_BUCKET"], creds["KB_S3_REGION"]
    host = f"{bucket}.{region}.your-objectstorage.com"
    content_type = CONTENT_TYPES.get(local.suffix, "application/octet-stream")
    amzdate = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    body = local.read_bytes()
    payload_sha = hashlib.sha256(body).hexdigest()
    request = urllib.request.Request(
        f"https://{host}/{key.lstrip('/')}", data=body, method="PUT",
        headers={
            "Host": host,
            "Content-Type": content_type,
            "x-amz-content-sha256": payload_sha,
            "x-amz-date": amzdate,
            "Authorization": authorization(
                creds["KB_S3_ACCESS_KEY"], creds["KB_S3_SECRET_KEY"],
                region, host, key, content_type, amzdate, payload_sha),
            # Cache forever: the key is a hash of the render's own inputs, so these bytes
            # can never change under this key. A new render is a new key, which is the
            # whole reason the keys are content-addressed.
            "Cache-Control": "public, max-age=31536000, immutable",
        })
    # RETRIED, BECAUSE A 3.5-HOUR RUN MUST NOT DIE ON ONE TRANSIENT GATEWAY ERROR. It did:
    # `render_boards.py` crashed at board file 190/388 on an unhandled 504 from Hetzner,
    # taking the rest of the corpus with it even though nothing about the request was
    # wrong -- a retry of the identical PUT would very likely have succeeded. 5xx and a
    # bare connection failure are worth retrying; 4xx (bad signature, bad bucket, wrong
    # key) is not -- retrying a request that is wrong will only ever get the same 4xx back,
    # slower.
    last_exc = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return response.status, response.headers.get("ETag", "")
        except urllib.error.HTTPError as e:
            if e.code < 500:
                raise
            last_exc = e
        except (urllib.error.URLError, TimeoutError) as e:
            last_exc = e
        if attempt < 3:
            time.sleep(2 ** attempt)
    raise last_exc


def public_url(key, creds=None):
    """The bucket URL for a key. Public-read, so this needs no signature."""
    creds = creds or credentials()
    return (f"https://{creds['KB_S3_BUCKET']}.{creds['KB_S3_REGION']}"
            f".your-objectstorage.com/{key.lstrip('/')}")


def exists(key, creds=None):
    """Is this key already in the bucket?

    An UNSIGNED HEAD, because the bucket is public-read -- so the cheapest possible
    existence check needs no credential and no signing at all. Safe to trust because the
    keys are content-addressed: a key being present means those exact bytes are present,
    so "exists" and "up to date" are the same question here.
    """
    request = urllib.request.Request(public_url(key, creds), method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status == 200
    except urllib.error.HTTPError:
        return False
    except (urllib.error.URLError, TimeoutError):
        # A network failure is not an absence. Returning False would merely re-upload,
        # which is safe; returning True would skip an upload that never happened.
        #
        # `TimeoutError` IS LISTED SEPARATELY FROM `URLError` AND THAT IS NOT REDUNDANT:
        # a timeout establishing the connection comes back wrapped as `URLError`, but a
        # stall reading the response body after the connection succeeds surfaces as a
        # bare `TimeoutError` that `urlopen` does not wrap -- confirmed the hard way, a
        # 3.5-hour corpus run crashed here on exactly that, mid-run, at board file 220.
        # This function already correctly reasoned about "a network failure is not an
        # absence"; it simply had not been told this WAS one.
        return False


def selftest():
    """Make curl sign a real request, then reproduce its signature here. Live oracle."""
    creds = {"KB_S3_ACCESS_KEY": "AKIAIOSFODNN7EXAMPLE",
             "KB_S3_SECRET_KEY": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
             "KB_S3_BUCKET": "keyboardbuilders-renders", "KB_S3_REGION": "hel1"}
    host = f"{creds['KB_S3_BUCKET']}.{creds['KB_S3_REGION']}.your-objectstorage.com"
    key, content_type = "selftest/vector.txt", "text/plain"
    payload = b"selftest"
    payload_sha = hashlib.sha256(payload).hexdigest()

    # Dummy credentials on purpose: signing is arithmetic and needs no valid key, so this
    # verifies the algorithm without ever touching a real secret. The store answers 403,
    # which is the expected and harmless outcome -- curl has already signed by then.
    # `-v`, NOT `--trace-ascii`: the trace hexdumps and wraps a long header across
    # several lines, which silently truncated the signature the first time this ran.
    # Verbose prints each request header on one line prefixed `> `, on stderr.
    trace = subprocess.run(
        ["curl", "-s", "-v", "-o", "/dev/null",
         "--aws-sigv4", f"aws:amz:{creds['KB_S3_REGION']}:s3",
         "--user", f"{creds['KB_S3_ACCESS_KEY']}:{creds['KB_S3_SECRET_KEY']}",
         "-X", "PUT", "--data-binary", payload.decode(),
         "-H", f"content-type: {content_type}",
         f"https://{host}/{key}"],
        capture_output=True, text=True, timeout=60).stderr

    sent_auth = re.search(r"Authorization: (AWS4-HMAC-SHA256 [^\r\n]+)", trace)
    sent_date = re.search(r"x-amz-date: (\d{8}T\d{6}Z)", trace)
    if not sent_auth or not sent_date:
        print("could not read curl's own signature from the trace; is curl >= 7.75?")
        return 1
    curl_sig = sent_auth.group(1).split("Signature=")[1].strip()
    # The digest curl actually sent, read back rather than assumed -- the whole point.
    sent_sha = re.search(r"x-amz-content-sha256: ([0-9a-f]{64})", trace)
    if sent_sha and sent_sha.group(1) != payload_sha:
        print(f"  curl signed a payload digest we did not expect: {sent_sha.group(1)}")
        return 1
    mine = authorization(creds["KB_S3_ACCESS_KEY"], creds["KB_S3_SECRET_KEY"],
                         creds["KB_S3_REGION"], host, key, content_type,
                         sent_date.group(1), payload_sha).split("Signature=")[1]

    print(f"  curl   {curl_sig}\n  stdlib {mine}\n  {'MATCH' if mine == curl_sig else 'MISMATCH'}")
    if mine != curl_sig:
        print(f"\n  curl sent: {sent_auth.group(1)}")
        return 1
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--put", metavar="FILE")
    ap.add_argument("--key")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.put:
        if not args.key:
            sys.exit("--put needs --key")
        status, etag = put(args.put, args.key)
        print(f"  {status} {args.key} etag={etag}")
        return 0
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
