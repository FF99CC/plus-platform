"""Local-only provisioning/test client. Requires private config and local Docker access.

Browser captcha follows CAPTCHA_ENABLED. When enabled, automated setup reads only its
own one-time captcha from the isolated Valkey instance; no endpoint or bypass is added.
Tokens stay in memory and are never printed.
"""
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
from .local_config import ConfigError
from .runtime import compose, docker_ready

CLIENT_ID='e5cd7e4891bf95d1d19206ce24a7b32e'

class LocalApi:
    def __init__(self, root, config):
        self.root=root; self.config=config; self.token=None
        self.base='http://127.0.0.1:'+config['WEB_PORT']+'/prod-api'
    def request(self,path,method='GET',body=None,raw=None,content_type=None,check=True):
        headers={'clientid':CLIENT_ID}
        if self.token: headers['Authorization']='Bearer '+self.token
        if body is not None:
            raw=json.dumps(body,ensure_ascii=False).encode(); content_type='application/json'
        if content_type: headers['Content-Type']=content_type
        req=urllib.request.Request(self.base+path,data=raw,headers=headers,method=method)
        try:
            with urllib.request.urlopen(req,timeout=45) as response: data=json.load(response)
        except urllib.error.HTTPError as error:
            try: data=json.load(error)
            except ValueError: data={'code':error.code,'msg':'HTTP request failed'}
        if check and data.get('code') != 200:
            raise ConfigError('%s %s 失败 [%s]：%s' % (method,path,data.get('code'),data.get('msg')))
        return data
    def login(self,account,key):
        data=self.request('/auth/code')['data']; code=''
        if data.get('captchaEnabled',True):
            docker_ready()
            ident=data['uuid']
            if not re.fullmatch(r'[A-Za-z0-9_-]+',ident): raise ConfigError('无效验证码标识')
            out=compose(self.root,self.config,['exec','-T','redis','sh','-c',
                'valkey-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw GET "$1"',
                'local-captcha','global:captcha_codes:'+ident],capture=True).stdout.strip()
            try: code=str(json.loads(out))
            except (ValueError,TypeError): code=out
            if not code: raise ConfigError('本地初始化验证码已失效，请重试。')
        payload={'username':account,'password':self.config[key],'clientId':CLIENT_ID,
                 'grantType':'password','uuid':data.get('uuid'),'code':code}
        data=self.request('/auth/login','POST',payload)['data']
        self.token=data.get('access_token',data.get('accessToken'))
        if not self.token: raise ConfigError('登录未返回令牌')
        return self
    def import_flow(self,definition):
        boundary='----LocalPlus'+uuid.uuid4().hex
        encoded=json.dumps(definition,ensure_ascii=False).encode()
        body=(('--'+boundary+'\r\nContent-Disposition: form-data; name="category"\r\n\r\n'+definition['category']+'\r\n').encode()+
              ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="flow.json"\r\nContent-Type: application/json\r\n\r\n').encode()+encoded+
              ('\r\n--'+boundary+'--\r\n').encode())
        return self.request('/workflow/definition/importDef','POST',raw=body,content_type='multipart/form-data; boundary='+boundary)
