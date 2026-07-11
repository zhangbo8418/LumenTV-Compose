import re
import os
import sys
import json
import time
import requests
from lxml import etree
from abc import abstractmethod, ABCMeta
from importlib.machinery import SourceFileLoader


def _proxy_port():
    return int(os.environ.get('LUMEN_PROXY_PORT', '9978'))


def _py_cache_dir():
    return os.environ.get('LUMEN_PY_CACHE', os.path.join(os.path.expanduser('~'), '.cache', 'Lumen-TV', 'data', 'cache', 'py'))


class Spider(metaclass=ABCMeta):
    _instance = None

    def __init__(self):
        self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        cls._instance = super().__new__(cls)
        return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        cache_dir = _py_cache_dir()
        path = os.path.join(cache_dir, f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=False, stream=False, allow_redirects=True):
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=False, stream=False, allow_redirects=True):
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(self, text):
        return json.loads(text)

    def json2str(self, text):
        return json.dumps(text, ensure_ascii=False)

    def getProxyUrl(self, local=True):
        host = '127.0.0.1' if local else '0.0.0.0'
        return f'http://{host}:{_proxy_port()}/proxy?do=py'

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False), file=sys.stderr)
        else:
            print(f'{msg}', file=sys.stderr)

    def _cache_file(self, key):
        cache_dir = os.path.join(_py_cache_dir(), 'kv')
        os.makedirs(cache_dir, exist_ok=True)
        safe_key = re.sub(r'[^a-zA-Z0-9._-]', '_', key)
        return os.path.join(cache_dir, f'{safe_key}.json')

    def getCache(self, key):
        cache_file = self._cache_file(key)
        if not os.path.exists(cache_file):
            return None
        with open(cache_file, 'r', encoding='utf-8') as f:
            value = f.read()
        if not value:
            return None
        if (value.startswith('{') and value.endswith('}')) or (value.startswith('[') and value.endswith(']')):
            value = json.loads(value)
            if isinstance(value, dict):
                if 'expiresAt' not in value or value['expiresAt'] >= int(time.time()):
                    return value
                self.delCache(key)
                return None
        return value

    def setCache(self, key, value):
        if isinstance(value, (int, float)):
            value = str(value)
        if value:
            if isinstance(value, (dict, list)):
                value = json.dumps(value, ensure_ascii=False)
        cache_file = self._cache_file(key)
        with open(cache_file, 'w', encoding='utf-8') as f:
            f.write(value or '')
        return 'succeed'

    def delCache(self, key):
        cache_file = self._cache_file(key)
        if os.path.exists(cache_file):
            os.remove(cache_file)
        return 'succeed'
