#!/usr/bin/env python3
import json
import os
import ssl
import sys
import traceback

# embed Python（._pth 隔离）不会把脚本目录加入 sys.path；
# 必须在 import app 之前把 runner / cache 目录挂上。
_runner_dir = os.path.dirname(os.path.abspath(__file__))
_cache_dir = sys.argv[1] if len(sys.argv) > 1 else _runner_dir
for _p in (_runner_dir, _cache_dir):
    if _p and _p not in sys.path:
        sys.path.insert(0, _p)


def _ensure_ssl_certs():
    """桌面 Python 常缺 CA；对齐可用 HTTPS（TV/Android 系统证书正常）。"""
    candidates = []
    try:
        import certifi
        candidates.append(certifi.where())
    except Exception:
        pass
    candidates.extend([
        os.environ.get('SSL_CERT_FILE', ''),
        os.environ.get('REQUESTS_CA_BUNDLE', ''),
        '/etc/ssl/cert.pem',
        '/etc/ssl/certs/ca-certificates.crt',
        '/etc/pki/tls/certs/ca-bundle.crt',
    ])
    try:
        import glob
        candidates.extend(glob.glob('/opt/homebrew/etc/openssl@*/cert.pem'))
        candidates.extend(glob.glob('/usr/local/etc/openssl@*/cert.pem'))
        candidates.extend(glob.glob('/Library/Frameworks/Python.framework/Versions/*/etc/openssl/cert.pem'))
    except Exception:
        pass

    cafile = next((p for p in candidates if p and os.path.isfile(p)), None)
    if cafile:
        os.environ.setdefault('SSL_CERT_FILE', cafile)
        os.environ.setdefault('REQUESTS_CA_BUNDLE', cafile)
        os.environ.setdefault('CURL_CA_BUNDLE', cafile)
        try:
            ssl._create_default_https_context = lambda: ssl.create_default_context(cafile=cafile)
        except Exception:
            pass
        return
    # 仍找不到证书时降级，避免新浪等源整站不可用
    try:
        ssl._create_default_https_context = ssl._create_unverified_context
    except Exception:
        pass


_ensure_ssl_certs()

import app

cache = sys.argv[1]
script_path = sys.argv[2]
site_key = sys.argv[3]
api = sys.argv[4] if len(sys.argv) > 4 else script_path

if cache not in sys.path:
    sys.path.insert(0, cache)

ru = app.spider(cache, script_path)
ru.siteKey = site_key


def handle(req):
    method = req.get("method")
    args = req.get("args", [])
    if method == "init":
        app.init(ru, args[0] if args else "", api, cache)
        return ""
    if method == "homeContent":
        return app.homeContent(ru, bool(args[0]) if args else False)
    if method == "homeVideoContent":
        return app.homeVideoContent(ru)
    if method == "categoryContent":
        return app.categoryContent(ru, args[0], args[1], bool(args[2]), args[3])
    if method == "detailContent":
        return app.detailContent(ru, args[0])
    if method == "searchContent":
        if len(args) >= 3:
            return app.searchContent(ru, args[0], bool(args[1]), args[2])
        return app.searchContent(ru, args[0], bool(args[1]))
    if method == "playerContent":
        return app.playerContent(ru, args[0], args[1], args[2])
    if method == "liveContent":
        return app.liveContent(ru, args[0])
    if method == "localProxy":
        return app.localProxy(ru, args[0])
    if method == "action":
        return app.action(ru, args[0])
    if method == "destroy":
        app.destroy(ru)
        return ""
    raise ValueError(f"unknown method: {method}")


for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    req = json.loads(line)
    try:
        result = handle(req)
        print(json.dumps({"id": req.get("id"), "ok": True, "result": result}, ensure_ascii=False), flush=True)
    except Exception as e:
        traceback.print_exc(file=sys.stderr)
        print(json.dumps({"id": req.get("id"), "ok": False, "error": str(e)}, ensure_ascii=False), flush=True)
