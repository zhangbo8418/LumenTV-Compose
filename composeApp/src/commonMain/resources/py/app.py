import os
import shutil
import requests
from importlib.machinery import SourceFileLoader
import json
from urllib.parse import urljoin


def spider(cache, api):
    if os.path.isfile(api) and api.endswith('.py'):
        path = api
    else:
        path = materialize(cache, api)
    name = os.path.splitext(os.path.basename(path))[0]
    return SourceFileLoader(name, path).load_module().Spider()


def materialize(cache, api):
    name = os.path.basename(api)
    path = os.path.join(cache, name)
    download(path, api, cache)
    return path


def download(path, api, cache):
    if os.path.abspath(api) == os.path.abspath(path):
        return
    if api.startswith('http'):
        writeFile(path, redirect(api).content)
    elif os.path.isfile(api):
        shutil.copyfile(api, path)
    elif os.path.isfile(os.path.join(cache, api)):
        shutil.copyfile(os.path.join(cache, api), path)
    else:
        raise FileNotFoundError(f'无法加载 Python 脚本: {api}')


def writeFile(path, content):
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    rsp = requests.get(url, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'])
    else:
        return rsp


def resolve_url(base, name):
    if not name:
        return name
    if name.startswith('http'):
        return name
    if base.startswith('http'):
        return urljoin(base, name)
    return name


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend, api, cache):
    dependence = ru.getDependence() if hasattr(ru, 'getDependence') else []
    if dependence:
        for item in dependence:
            dep_name = item if item.endswith('.py') else f'{item}.py'
            dep_path = os.path.join(cache, os.path.basename(dep_name))
            dep_url = resolve_url(api, dep_name)
            download(dep_path, dep_url, cache)
    ru.init(extend)


def _dumps(result):
    # Spider 常用 pass/None；json.dumps(None) 会变成 "null"，宿主 JSON 解析直接炸
    if result is None:
        result = {}
    return json.dumps(result, ensure_ascii=False)


def homeContent(ru, filter):
    return _dumps(ru.homeContent(filter))


def homeVideoContent(ru):
    return _dumps(ru.homeVideoContent())


def categoryContent(ru, tid, pg, filter, extend):
    return _dumps(ru.categoryContent(tid, pg, filter, str2json(extend)))


def detailContent(ru, array):
    return _dumps(ru.detailContent(str2json(array)))


def searchContent(ru, key, quick, pg="1"):
    return _dumps(ru.searchContent(key, quick, pg))


def playerContent(ru, flag, id, vipFlags):
    return _dumps(ru.playerContent(flag, id, str2json(vipFlags)))


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    return _dumps(ru.action(action))


def destroy(ru):
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()
