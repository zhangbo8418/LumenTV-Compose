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


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()
