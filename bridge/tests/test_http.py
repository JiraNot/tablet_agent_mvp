import json, pathlib, sys, tempfile, threading, unittest
from urllib.error import HTTPError
from urllib.request import Request,urlopen
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from server import create_server

class ApiTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.server,self.core=create_server(pathlib.Path(self.temp.name),0)
        self.t=threading.Thread(target=self.server.serve_forever,daemon=True);self.t.start()
        self.addCleanup(self.server.server_close);self.addCleanup(self.server.shutdown)
        self.base='http://127.0.0.1:'+str(self.server.server_address[1])
    def call(self,method,path,obj=None):
        body=None if obj is None else json.dumps(obj).encode()
        r=Request(self.base+path,data=body,headers={'Authorization':'Bearer '+self.core.token,'Content-Type':'application/json'},method=method)
        with urlopen(r,timeout=4) as resp:return resp.status,json.loads(resp.read())
    def test_create_file_task_workflow(self):
        status,proj=self.call('POST','/v1/projects',{'name':'tablet-demo'})
        self.assertEqual(status,201)
        status,files=self.call('GET','/v1/projects/tablet-demo/files')
        self.assertTrue(any(x['name']=='README.md' for x in files['files']))
        status,task=self.call('POST','/v1/tasks',{'project':'tablet-demo','prompt':'Make a website','mode':'build'})
        self.assertEqual(status,202)
        _,items=self.call('GET','/v1/tasks?project=tablet-demo')
        self.assertTrue(any(x['id']==task['id'] for x in items['tasks']))
        self.call('POST',f'/v1/tasks/{task["id"]}/cancel')
    def test_file_put_get_and_reject_outside(self):
        self.call('POST','/v1/projects',{'name':'p'})
        _,data=self.call('PUT','/v1/projects/p/file?path=.env',{'content':'A=1'})
        self.assertEqual(data['bytes'],3)
        _,data=self.call('GET','/v1/projects/p/file?path=.env')
        self.assertEqual(data['content'],'A=1')
        with self.assertRaises(HTTPError) as e:self.call('GET','/v1/projects/p/file?path=..%2F..%2Fetc%2Fpasswd')
        self.assertEqual(e.exception.code,403)
    def test_reject_untrusted_git_host(self):
        with self.assertRaises(HTTPError) as e:self.call('POST','/v1/projects',{'name':'x','git_url':'https://evil.invalid/x/y'})
        self.assertEqual(e.exception.code,400)
    def test_project_instructions_get_and_put(self):
        self.call('POST', '/v1/projects', {'name': 'ins_proj'})
        st, res = self.call('GET', '/v1/projects/ins_proj/instructions')
        self.assertEqual(res['file'], 'AGENTS.md')
        self.assertEqual(res['content'], '')
        st, res2 = self.call('PUT', '/v1/projects/ins_proj/instructions', {'content': '# Guidelines\n- Rule 1'})
        self.assertEqual(res2['path'], 'AGENTS.md')
        st, res3 = self.call('GET', '/v1/projects/ins_proj/instructions')
        self.assertEqual(res3['file'], 'AGENTS.md')
        self.assertIn('Rule 1', res3['content'])

if __name__=='__main__':unittest.main()
