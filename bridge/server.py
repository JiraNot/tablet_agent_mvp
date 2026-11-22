#!/usr/bin/env python3
"""HTTP API bound exclusively to Android loopback. No third-party Python packages."""
from __future__ import annotations
import json
import os
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

sys.path.insert(0,str(Path(__file__).resolve().parent))
from core import LocalCore, BridgeError

class Handler(BaseHTTPRequestHandler):
    core: LocalCore
    server_version = 'TabletAgentBridge/0.1'

    def log_message(self, format, *args):
        # Avoid logging URL parameters or secrets from client requests.
        pass

    def reply(self, code: int, obj):
        payload=json.dumps(obj,ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Length',str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _body(self):
        try: n=int(self.headers.get('Content-Length','0'))
        except ValueError:raise BridgeError('Invalid Content-Length')
        if not 0<=n<=1024*1024:raise BridgeError('Payload must be <= 1 MiB',413)
        if n==0:return {}
        try:
            b=json.loads(self.rfile.read(n).decode('utf-8'))
            if not isinstance(b,dict):raise ValueError()
            return b
        except (ValueError,UnicodeError):raise BridgeError('Expected JSON object')

    def _dispatch(self):
        path=urlsplit(self.path)
        seg=[unquote(x) for x in path.path.split('/') if x]
        q=parse_qs(path.query)
        if self.command=='GET' and path.path=='/health':
            runtime='embedded' if os.getenv('TABLET_AGENT_EMBEDDED')=='1' else 'termux'
            return self.reply(200,{'ok':True,'service':'tablet-agent-local','version':'0.1.0','runtime':runtime})
        bearer=self.headers.get('Authorization','')
        import secrets
        if not secrets.compare_digest(bearer,'Bearer '+self.core.token):
            return self.reply(401,{'error':'Invalid device token'})
        method=self.command
        if seg==['v1','projects']:
            if method=='GET':return self.reply(200,{'projects':self.core.projects()})
            if method=='POST':
                obj=self._body();return self.reply(201,self.core.create_project(str(obj.get('name','')),str(obj.get('git_url',''))))
        if seg==['v1','agents'] and method=='GET':
            return self.reply(200,{'agents':self.core.available_agents()})
        if seg==['v1','settings']:
            if method=='GET':return self.reply(200,self.core.get_settings())
            if method=='PUT':return self.reply(200,self.core.update_settings(self._body()))
        if seg==['v1','tasks']:
            if method=='GET':return self.reply(200,{'tasks':self.core.tasks(q.get('project',[None])[0])})
            if method=='POST':
                obj=self._body();return self.reply(202,self.core.start_task(str(obj.get('project','')),str(obj.get('prompt','')),str(obj.get('mode','build')),str(obj.get('agent','codex')),str(obj.get('model',''))))
        if len(seg)>=3 and seg[:2]==['v1','tasks']:
            tid=seg[2]
            if len(seg)==3 and method=='GET':return self.reply(200,self.core.task(tid))
            if len(seg)==4 and seg[3]=='events' and method=='GET':
                try: after=int(q.get('after',[0])[0])
                except (ValueError,TypeError):raise BridgeError('Invalid sequence')
                return self.reply(200,self.core.events_page(tid,after))
            if len(seg)==4 and seg[3]=='approvals' and method=='GET':
                return self.reply(200,{'approvals':self.core.approvals(tid)})
            if len(seg)==5 and seg[3]=='approvals' and method=='POST':
                aid=seg[4]
                obj=self._body()
                decision=str(obj.get('decision','deny'))
                return self.reply(200,self.core.resolve_approval(tid,aid,decision))
            if len(seg)==4 and seg[3]=='cancel' and method=='POST':
                return self.reply(200,self.core.cancel(tid))
        if len(seg)>=3 and seg[:2]==['v1','projects']:
            name=seg[2]
            if len(seg)==4 and seg[3]=='files' and method=='GET':
                return self.reply(200,{'files':self.core.list_files(name,q.get('path',['.'])[0])})
            if len(seg)==4 and seg[3]=='file':
                rel=q.get('path',[''])[0]
                if method=='GET':return self.reply(200,self.core.read_file(name,rel))
                if method=='PUT':return self.reply(200,self.core.write_file(name,rel,str(self._body().get('content',''))))
            if len(seg)==4 and seg[3]=='instructions':
                if method=='GET':
                    return self.reply(200,self.core.project_instructions(name))
                if method=='PUT':
                    return self.reply(200,self.core.save_project_instructions(name,str(self._body().get('content',''))))
            if len(seg)==5 and seg[3]=='git':
                if seg[4]=='status' and method=='GET':
                    return self.reply(200,self.core.git_status(name))
                if seg[4]=='diff' and method=='GET':
                    return self.reply(200,self.core.git_diff(name))
                if seg[4]=='commit' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_commit(name,str(obj.get('message',''))))
                if seg[4]=='rollback' and method=='POST':
                    obj=self._body()
                    target_sha=obj.get('sha')
                    return self.reply(200,self.core.git_rollback(name,str(target_sha) if target_sha else None))
            if len(seg)>=4 and seg[3]=='preview':
                if len(seg)==4 and method=='GET':return self.reply(200,self.core.preview(name))
                if len(seg)==5 and seg[4]=='start' and method=='POST':
                    obj=self._body();port=obj.get('port')
                    return self.reply(200,self.core.start_preview(name,None if port is None else int(port)))
                if len(seg)==5 and seg[4]=='stop' and method=='POST':
                    return self.reply(200,self.core.stop_preview(name))
        raise BridgeError('Endpoint not found',404)

    def handle_one_request(self):
        super().handle_one_request()

    def _process(self):
        try:self._dispatch()
        except BridgeError as e:self.reply(e.status,{'error':str(e)})
        except (OSError,ValueError) as e:self.reply(400,{'error':str(e)[:200]})
        except Exception:
            traceback.print_exc()
            self.reply(500,{'error':'Internal bridge error'})
    do_GET = _process
    do_POST = _process
    do_PUT = _process

def create_server(home:Path,port:int=8765):
    bridge=LocalCore(home)
    class BoundHandler(Handler):
        core=bridge
    server=ThreadingHTTPServer(('127.0.0.1',port),BoundHandler)
    server.daemon_threads=True
    return server,bridge

if __name__=='__main__':
    home=Path(os.getenv('TABLET_AGENT_HOME',str(Path.home()/'tablet-agent')))
    port=int(os.getenv('TABLET_AGENT_PORT','8765'))
    server,core=create_server(home,port)
    print('Local Tablet Agent API: http://127.0.0.1:%d'%port,flush=True)
    if os.getenv('TABLET_AGENT_EMBEDDED')!='1':
        print('Device token (paste once in native app Settings):',core.token,flush=True)
    print('Workspace:',core.workspaces,flush=True)
    try:server.serve_forever(poll_interval=.25)
    except KeyboardInterrupt:pass
    finally:server.server_close()
