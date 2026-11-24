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

    def _body(self, max_bytes: int = 1024 * 1024):
        try: n=int(self.headers.get('Content-Length','0'))
        except ValueError:raise BridgeError('Invalid Content-Length')
        if not 0<=n<=max_bytes:raise BridgeError(f'Payload must be <= {max_bytes // (1024*1024)} MiB',413)
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
                obj=self._body();return self.reply(201,self.core.create_project(str(obj.get('name','')),str(obj.get('git_url','')),str(obj.get('token',''))))
        if seg==['v1','projects','import'] and method=='POST':
            obj=self._body()
            return self.reply(201,self.core.import_project(str(obj.get('zip_path','')), str(obj.get('name','')) or None))
        if seg==['v1','templates','init'] and method=='POST':
            obj=self._body()
            return self.reply(201,self.core.templates_init(str(obj.get('project','')), str(obj.get('template_id','react_vite'))))
        if seg==['v1','agents'] and method=='GET':
            return self.reply(200,{'agents':self.core.available_agents()})
        if seg==['v1','slm','status'] and method=='GET':
            return self.reply(200,self.core.slm_status())
        if seg==['v1','slm','setup'] and method=='POST':
            return self.reply(200,self.core.slm_setup(self._body()))
        if seg==['v1','slm','download'] and method=='POST':
            return self.reply(200,self.core.slm_download(self._body()))
        if seg==['v1','settings']:
            if method=='GET':return self.reply(200,self.core.get_settings())
            if method=='PUT':return self.reply(200,self.core.update_settings(self._body()))
        if seg==['v1','tasks']:
            if method=='GET':return self.reply(200,{'tasks':self.core.tasks(q.get('project',[None])[0])})
            if method=='POST':
                obj=self._body();return self.reply(202,self.core.start_task(str(obj.get('project','')),str(obj.get('prompt','')),str(obj.get('mode','build')),str(obj.get('agent','codex')),str(obj.get('model','')),str(obj.get('task_id','')) or None,bool(obj.get('use_worktree',False))))
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
            if len(seg)==3 and method=='DELETE':
                return self.reply(200,self.core.delete_project(name))
            if len(seg)==4 and seg[3]=='rename' and method=='POST':
                obj=self._body()
                return self.reply(200,self.core.rename_project(name,str(obj.get('new_name',''))))
            if len(seg)==4 and seg[3]=='search' and method=='GET':
                query_str=q.get('q',[''])[0]
                cs=q.get('case_sensitive',['false'])[0].lower()=='true'
                return self.reply(200,self.core.search_files(name,query_str,case_sensitive=cs))
            if len(seg)==4 and seg[3]=='files' and method=='GET':
                return self.reply(200,{'files':self.core.list_files(name,q.get('path',['.'])[0])})
            if len(seg)==4 and seg[3]=='file':
                rel=q.get('path',[''])[0]
                if method=='GET':return self.reply(200,self.core.read_file(name,rel))
                if method=='PUT':
                    obj=self._body()
                    return self.reply(200,self.core.write_file(name,rel,str(obj.get('content','')),create_dirs=bool(obj.get('create_dirs',False))))
            if len(seg)==4 and seg[3]=='instructions':
                if method=='GET':
                    return self.reply(200,self.core.project_instructions(name))
                if method=='PUT':
                    return self.reply(200,self.core.save_project_instructions(name,str(self._body().get('content',''))))
            if len(seg)==4 and seg[3]=='export' and method=='POST':
                obj=self._body()
                return self.reply(200,self.core.export_project(name,include_env=bool(obj.get('include_env',False))))
            if len(seg)==4 and seg[3]=='attachments' and method=='POST':
                obj=self._body(max_bytes=36*1024*1024)
                return self.reply(201,self.core.save_attachment(name,str(obj.get('filename','')),str(obj.get('data_b64',''))))
            if len(seg)==4 and seg[3]=='apks' and method=='GET':
                return self.reply(200,{'apks':self.core.list_project_apks(name)})
            if len(seg)==4 and seg[3]=='memory' and method=='GET':
                try:
                    return self.reply(200,self.core.read_file(name,'.agents/memory.md'))
                except Exception:
                    return self.reply(200,{'path':'.agents/memory.md','content':''})
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
                if seg[4]=='branches' and method=='GET':
                    return self.reply(200,self.core.git_branches(name))
                if seg[4]=='checkout' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_checkout(name,str(obj.get('branch','')),create=bool(obj.get('create',False))))
                if seg[4]=='push' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_push(name,str(obj.get('remote','origin')),str(obj.get('branch',''))))
                if seg[4]=='create-and-push' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_create_and_push(name,repo_name=str(obj.get('repo_name','')),is_private=bool(obj.get('private',True)),description=str(obj.get('description','')),token=str(obj.get('token',''))))
                if seg[4]=='checkpoint' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_checkpoint(name,str(obj.get('label','manual'))))
                if seg[4]=='log' and method=='GET':
                    lim=int(q.get('limit',['50'])[0])
                    return self.reply(200,self.core.git_log(name,limit=lim))
                if seg[4]=='pull' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_pull(name,str(obj.get('remote','origin')),str(obj.get('branch','')),token=str(obj.get('token',''))))
                if seg[4]=='discard' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_discard(name,str(obj.get('path','')),discard_all=bool(obj.get('all',False))))
                if seg[4]=='file-diff' and method=='GET':
                    rel=q.get('path',[''])[0]
                    return self.reply(200,self.core.git_diff_file(name,rel))
                if seg[4]=='ai-commit' and method=='POST':
                    return self.reply(200,self.core.git_ai_commit(name))
                if seg[4]=='draft-pr' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_draft_pr(name,base_branch=str(obj.get('base','main'))))
                if seg[4]=='create-pr' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.git_create_pr(name,title=str(obj.get('title','')),body=str(obj.get('body','')),head=str(obj.get('head','')),base=str(obj.get('base','main')),token=str(obj.get('token',''))))
                if seg[4]=='resolve-conflicts' and method=='POST':
                    return self.reply(200,self.core.git_resolve_conflicts(name))
                if seg[4]=='audit' and method=='POST':
                    return self.reply(200,self.core.git_audit(name))
            if len(seg)>=4 and seg[3]=='worktrees':
                if len(seg)==4 and method=='GET':
                    return self.reply(200,{'worktrees':self.core.list_worktrees(name)})
                if len(seg)==4 and method=='POST':
                    obj=self._body()
                    tid=str(obj.get('task_id','manual'))
                    return self.reply(201,{'path':str(self.core.create_worktree(name,tid))})
                if len(seg)==4 and method=='DELETE':
                    obj=self._body()
                    tid=str(obj.get('task_id','manual'))
                    self.core.cleanup_worktree(name,tid)
                    return self.reply(200,{'ok':True})
                if len(seg)==5 and seg[4]=='prune' and method=='POST':
                    return self.reply(200,self.core.prune_worktrees(name))
            if len(seg)==5 and seg[3]=='terminal':
                if seg[4]=='start' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.terminal_start(name,int(obj.get('cols',80)),int(obj.get('rows',24))))
                if seg[4]=='input' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.terminal_input(name,str(obj.get('data',''))))
                if seg[4]=='poll' and method=='GET':
                    try: after=int(q.get('after',[0])[0])
                    except (ValueError,TypeError): raise BridgeError('Invalid cursor')
                    return self.reply(200,self.core.terminal_poll(name,after))
                if seg[4]=='resize' and method=='POST':
                    obj=self._body()
                    return self.reply(200,self.core.terminal_resize(name,int(obj.get('cols',80)),int(obj.get('rows',24))))
                if seg[4]=='stop' and method=='POST':
                    return self.reply(200,self.core.terminal_stop(name))
            if len(seg)>=4 and seg[3]=='preview':
                if len(seg)==4 and method=='GET':return self.reply(200,self.core.preview(name))
                if len(seg)==5 and seg[4]=='start' and method=='POST':
                    obj=self._body();port=obj.get('port')
                    return self.reply(200,self.core.start_preview(name,None if port is None else int(port)))
                if len(seg)==5 and seg[4]=='stop' and method=='POST':
                    return self.reply(200,self.core.stop_preview(name))
            if len(seg)>=4 and seg[3]=='pwa':
                if len(seg)==5 and seg[4]=='configure' and method=='POST':
                    return self.reply(200,self.core.pwa_configure(name))
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
    do_DELETE = _process

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
