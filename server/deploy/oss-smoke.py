"""ECS-only OSS S3 protocol smoke test. No credentials or signed URLs are logged.

Uses the attached ECS RAM role; only removes the two unique objects it creates.
This checks the protocol, not Java SDK or browser CORS integration.
"""
import datetime
import hashlib
import hmac
import json
import struct
import zlib
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

BUCKET = "image-admin-prod-ww"
ROLE = "image-admin-prod-ecs-role"
REGION = "cn-hangzhou"
META = "http://100.100.100.200/latest/"


def fetch(request):
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status, response.headers, response.read()


def credentials():
    token = fetch(urllib.request.Request(META + "api/token", method="PUT",
        headers={"X-aliyun-ecs-metadata-token-ttl-seconds": "600"}))[2].decode()
    data = fetch(urllib.request.Request(META + "meta-data/ram/security-credentials/" + ROLE,
        headers={"X-aliyun-ecs-metadata-token": token}))[2]
    value = json.loads(data)
    if value.get("Code") != "Success":
        raise RuntimeError("ECS role credentials unavailable")
    return value


def request(creds, method, key, body=None, extra=None, query=None, public=False):
    host = BUCKET + ".s3.oss-cn-hangzhou" + ("" if public else "-internal") + ".aliyuncs.com"
    path = "/" + urllib.parse.quote(key, safe="/~")
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    scope = now[:8] + "/" + REGION + "/s3/aws4_request"
    headers = {"host": host, **(extra or {})}
    if body is not None:
        headers["content-length"] = str(len(body))
    signed = ";".join(sorted(headers))
    params = {**(query or {}), "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": creds["AccessKeyId"] + "/" + scope,
        "X-Amz-Date": now, "X-Amz-Expires": "300", "X-Amz-SignedHeaders": signed,
        "X-Amz-Security-Token": creds["SecurityToken"]}
    encode = lambda value: urllib.parse.quote(value, safe="~")
    canonical_query = "&".join(encode(k) + "=" + encode(v) for k, v in sorted(params.items()))
    canonical = "\n".join([method, path, canonical_query,
        "".join(k + ":" + headers[k] + "\n" for k in sorted(headers)), signed, "UNSIGNED-PAYLOAD"])
    string_to_sign = "AWS4-HMAC-SHA256\n" + now + "\n" + scope + "\n" + hashlib.sha256(canonical.encode()).hexdigest()
    signing_key = ("AWS4" + creds["AccessKeySecret"]).encode()
    for item in [now[:8], REGION, "s3", "aws4_request"]:
        signing_key = hmac.new(signing_key, item.encode(), hashlib.sha256).digest()
    signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    url = "https://" + host + path + "?" + canonical_query + "&X-Amz-Signature=" + signature
    return fetch(urllib.request.Request(url, data=body, headers=headers, method=method))


def run():
    creds = credentials()
    name = "oss-smoke-" + uuid.uuid4().hex + ".png"
    source, target = "review/" + name, "library/" + name
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x20\x80\xff" * 8) * 8)) + chunk(b"IEND", b""))
    try:
        request(creds, "PUT", source, png, {"content-type": "image/png", "x-oss-forbid-overwrite": "true"}, public=True)
        print("PASS public signed PUT with fixed length/type and overwrite protection", flush=True)
        try:
            request(creds, "PUT", source, png, {"content-type": "image/png", "x-oss-forbid-overwrite": "true"}, public=True)
            raise RuntimeError("Overwrite unexpectedly allowed")
        except urllib.error.HTTPError as error:
            if error.code != 409:
                raise
        print("PASS reused upload cannot overwrite", flush=True)
        _, headers, _ = request(creds, "HEAD", source)
        assert int(headers["Content-Length"]) == len(png)
        assert request(creds, "GET", source)[2] == png
        print("PASS internal HEAD and GET", flush=True)
        request(creds, "GET", source, query={"x-oss-process": "image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82"}, public=True)
        print("PASS public signed thumbnail", flush=True)
        response = request(creds, "PUT", target, extra={"x-amz-copy-source": "/" + BUCKET + "/" + source})
        if ET.fromstring(response[2]).tag.endswith("Error"):
            raise RuntimeError("Copy returned embedded error")
        assert request(creds, "GET", target)[2] == png
        print("PASS copy and verify", flush=True)
    finally:
        for key in [source, target]:
            try:
                request(creds, "DELETE", key)
                print("CLEANED " + key, flush=True)
            except Exception:
                print("CLEANUP FAILED " + key, flush=True)


if __name__ == "__main__":
    try:
        run()
    except urllib.error.HTTPError as error:
        # Never print error URL: it contains a temporary authorization signature.
        try:
            root = ET.fromstring(error.read())
            print("FAIL HTTP", error.code, "Code", root.findtext("Code"), "Message", root.findtext("Message"))
        except Exception:
            print("FAIL HTTP", error.code)
        raise SystemExit(1)
    except Exception as error:
        print("FAIL", type(error).__name__)
        raise SystemExit(1)
