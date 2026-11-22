import json
import os
from pathlib import Path
import stat
import tempfile
import time
import unittest
from unittest.mock import patch

from bridge.core import LocalCore


FAKE_CODEX_APP_SERVER = """#!/usr/bin/env python3
import json
import os
import pathlib
import sys
import time

root = pathlib.Path(os.environ["FAKE_CODEX_LOG_DIR"])
calls_file = root / "rpc_calls.jsonl"
mode_env = os.environ.get("FAKE_APP_SERVER_SCENARIO", "normal")

if len(sys.argv) < 2 or sys.argv[1] != "app-server":
    sys.exit(2)

def emit(obj):
    sys.stdout.write(json.dumps(obj) + "\\n")
    sys.stdout.flush()

active_thread = "thread-appserver-123456"
for raw in sys.stdin:
    line = raw.strip()
    if not line:
        continue
    msg = json.loads(line)
    with calls_file.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(msg) + "\\n")

    method = msg.get("method")
    req_id = msg.get("id")
    params = msg.get("params") or {}

    if method == "initialize":
        emit({
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "userAgent": "tablet-agent/0.171.0",
                "codexHome": str(root),
                "platformFamily": "unix",
                "platformOs": "linux",
            },
        })
    elif method in ("thread/start", "thread/resume"):
        if method == "thread/resume":
            active_thread = params.get("threadId") or "thread-resumed-999999"
        else:
            active_thread = "thread-appserver-123456"
        emit({
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "thread": {"id": active_thread, "sessionId": active_thread},
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "thread/started",
            "params": {"thread": {"id": active_thread}},
        })
    elif method == "turn/start":
        turn_id = "turn-001"
        emit({
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {"turn": {"id": turn_id, "status": "inProgress"}},
        })
        emit({
            "jsonrpc": "2.0",
            "method": "turn/started",
            "params": {"threadId": active_thread, "turn": {"id": turn_id, "status": "inProgress"}},
        })

        if mode_env == "error":
            emit({
                "jsonrpc": "2.0",
                "method": "turn/completed",
                "params": {
                    "threadId": active_thread,
                    "turn": {
                        "id": turn_id,
                        "status": "failed",
                        "error": {"code": "model_error", "message": "Simulated upstream model failure"},
                        "items": [],
                    },
                },
            })
            continue

        if mode_env == "cancel":
            # Wait for turn/interrupt or termination
            for cancel_line in sys.stdin:
                cmsg = json.loads(cancel_line.strip())
                with calls_file.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps(cmsg) + "\\n")
                if cmsg.get("method") == "turn/interrupt":
                    emit({
                        "jsonrpc": "2.0",
                        "method": "turn/completed",
                        "params": {
                            "threadId": active_thread,
                            "turn": {"id": turn_id, "status": "interrupted", "items": []},
                        },
                    })
                    break
            continue

        if mode_env == "approval":
            emit({
                "jsonrpc": "2.0",
                "id": "srv-approval-1",
                "method": "item/commandExecution/requestApproval",
                "params": {
                    "kind": "command",
                    "threadId": active_thread,
                    "turnId": turn_id,
                    "itemId": "cmd-1",
                    "startedAtMs": 1000,
                    "environmentId": "local",
                    "command": "npm test",
                    "cwd": params.get("cwd", "."),
                },
            })
            resp_line = sys.stdin.readline().strip()
            resp = json.loads(resp_line)
            with calls_file.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(resp) + "\\n")
            decision = (resp.get("result") or {}).get("decision")
            if decision != "accept":
                emit({
                    "jsonrpc": "2.0",
                    "method": "turn/completed",
                    "params": {
                        "threadId": active_thread,
                        "turn": {
                            "id": turn_id,
                            "status": "failed",
                            "error": {"message": f"Approval declined: {decision}"},
                            "items": [],
                        },
                    },
                })
                continue

        # Stream tool + delta + completed message
        emit({
            "jsonrpc": "2.0",
            "method": "item/started",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "startedAtMs": 1000,
                "item": {
                    "type": "commandExecution",
                    "id": "item-tool-1",
                    "command": "ls -la",
                    "status": "inProgress",
                },
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "item/completed",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "completedAtMs": 1010,
                "item": {
                    "type": "commandExecution",
                    "id": "item-tool-1",
                    "command": "ls -la",
                    "status": "completed",
                },
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "item/started",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "startedAtMs": 1020,
                "item": {"type": "agentMessage", "id": "msg-1", "text": ""},
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "item/agentMessage/delta",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "itemId": "msg-1",
                "delta": "Structured ",
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "item/agentMessage/delta",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "itemId": "msg-1",
                "delta": "app-server response.",
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "item/completed",
            "params": {
                "threadId": active_thread,
                "turnId": turn_id,
                "completedAtMs": 1030,
                "item": {
                    "type": "agentMessage",
                    "id": "msg-1",
                    "text": "Structured app-server response.",
                },
            },
        })
        emit({
            "jsonrpc": "2.0",
            "method": "turn/completed",
            "params": {
                "threadId": active_thread,
                "turn": {
                    "id": turn_id,
                    "status": "completed",
                    "items": [
                        {
                            "type": "agentMessage",
                            "id": "msg-1",
                            "text": "Structured app-server response.",
                        }
                    ],
                },
            },
        })
"""


class CodexAppServerHarnessTests(unittest.TestCase):
    """M3 Adapter Test Harness covering start, stream, cancel, resume, error, approval, and reconnect."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        codex_py = self.bin_dir / "codex.py"
        codex_py.write_text(FAKE_CODEX_APP_SERVER, encoding="utf-8")
        codex_py.chmod(codex_py.stat().st_mode | stat.S_IEXEC)
        if os.name == "nt":
            import sys
            (self.bin_dir / "codex.cmd").write_text(f'@"{sys.executable}" "%~dp0codex.py" %*\r\n', encoding="utf-8")
        else:
            sh = self.bin_dir / "codex"
            sh.write_text(FAKE_CODEX_APP_SERVER, encoding="utf-8")
            sh.chmod(sh.stat().st_mode | stat.S_IEXEC)
        self.env = {
            "PATH": f"{self.bin_dir}{os.pathsep}{os.environ.get('PATH', '')}",
            "FAKE_CODEX_LOG_DIR": str(self.root),
        }
        self.core = LocalCore(self.root / "home")
        self.core.update_settings({"codex_use_app_server": True})
        self.core.create_project("app_proj")

    def tearDown(self):
        self.tmp.cleanup()

    def until_terminal(self, tid: str, timeout: float = 8.0) -> str:
        end = time.time() + timeout
        while time.time() < end:
            st = self.core.task(tid)["status"]
            if st in {"PLANNED", "COMPLETED", "NEEDS_VERIFICATION", "FAILED", "CANCELLED", "BLOCKED_SETUP", "INTERRUPTED"}:
                return st
            time.sleep(0.04)
        self.fail(f"Task {tid} did not reach terminal state; still {self.core.task(tid)['status']}")

    def read_rpc_calls(self) -> list[dict]:
        p = self.root / "rpc_calls.jsonl"
        if not p.exists():
            return []
        return [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]

    def test_start_stream_and_resume_over_app_server_json_rpc(self):
        with patch.dict(os.environ, self.env):
            t1 = self.core.start_task("app_proj", "First app-server plan", "plan")
            self.assertEqual(self.until_terminal(t1["id"]), "PLANNED")
            events = self.core.events_page(t1["id"], 0)["events"]
            kinds = [e["kind"] for e in events]
            self.assertIn("thread", kinds)
            self.assertIn("tool", kinds)
            self.assertIn("message", kinds)
            msgs = [e["message"] for e in events if e["kind"] == "message"]
            self.assertIn("Structured app-server response.", msgs)

            # Second turn in same project resumes the persisted thread via thread/resume
            t2 = self.core.start_task("app_proj", "Follow-up via app-server", "plan", task_id=t1["id"])
            self.assertEqual(self.until_terminal(t2["id"]), "PLANNED")

        calls = self.read_rpc_calls()
        methods = [c.get("method") for c in calls if "method" in c]
        self.assertIn("initialize", methods)
        self.assertIn("initialized", methods)
        self.assertIn("thread/start", methods)
        self.assertIn("turn/start", methods)
        self.assertIn("thread/resume", methods)
        resume_call = next(c for c in calls if c.get("method") == "thread/resume")
        self.assertEqual(resume_call["params"]["threadId"], "thread-appserver-123456")

    def test_app_server_approval_allow_and_deny(self):
        env_allow = {**self.env, "FAKE_APP_SERVER_SCENARIO": "approval"}
        with patch.dict(os.environ, env_allow):
            task = self.core.start_task("app_proj", "Run command requiring approval", "plan")
            # Wait until WAITING_APPROVAL and pending approval row appear
            deadline = time.time() + 5.0
            pending = []
            while time.time() < deadline:
                pending = [a for a in self.core.approvals(task["id"]) if a["status"] == "PENDING"]
                if pending:
                    break
                time.sleep(0.04)
            self.assertEqual(len(pending), 1)
            self.assertEqual(pending[0]["type"], "command_execution")
            self.assertEqual(pending[0]["target"], "npm test")

            # Approve via native supervisor API -> sends JSON-RPC result {decision: "accept"} to app-server stdin
            self.core.resolve_approval(task["id"], pending[0]["id"], "allow")
            self.assertEqual(self.until_terminal(task["id"]), "PLANNED")

        calls = self.read_rpc_calls()
        approval_resps = [c for c in calls if c.get("id") == "srv-approval-1"]
        self.assertEqual(len(approval_resps), 1)
        self.assertEqual(approval_resps[0]["result"]["decision"], "accept")

        # Now test denial
        (self.root / "rpc_calls.jsonl").unlink(missing_ok=True)
        with patch.dict(os.environ, env_allow):
            task2 = self.core.start_task("app_proj", "Run command that will be denied", "plan")
            deadline = time.time() + 5.0
            pending2 = []
            while time.time() < deadline:
                pending2 = [a for a in self.core.approvals(task2["id"]) if a["status"] == "PENDING"]
                if pending2:
                    break
                time.sleep(0.04)
            self.assertEqual(len(pending2), 1)
            self.core.resolve_approval(task2["id"], pending2[0]["id"], "deny")
            self.assertEqual(self.until_terminal(task2["id"]), "FAILED")

        calls2 = self.read_rpc_calls()
        deny_resps = [c for c in calls2 if c.get("id") == "srv-approval-1"]
        self.assertEqual(len(deny_resps), 1)
        self.assertEqual(deny_resps[0]["result"]["decision"], "decline")

    def test_app_server_error_and_cancel_and_reconnect_replay(self):
        # 1. Error handling
        env_err = {**self.env, "FAKE_APP_SERVER_SCENARIO": "error"}
        with patch.dict(os.environ, env_err):
            t_err = self.core.start_task("app_proj", "Trigger model error", "plan")
            self.assertEqual(self.until_terminal(t_err["id"]), "FAILED")
            err_events = [e for e in self.core.events_page(t_err["id"], 0)["events"] if e["kind"] == "error"]
            self.assertTrue(any("Simulated upstream model failure" in e["message"] for e in err_events))

        # 2. Mid-turn Cancel sending turn/interrupt
        (self.root / "rpc_calls.jsonl").unlink(missing_ok=True)
        env_cancel = {**self.env, "FAKE_APP_SERVER_SCENARIO": "cancel"}
        with patch.dict(os.environ, env_cancel):
            t_cancel = self.core.start_task("app_proj", "Long running turn to cancel", "plan")
            deadline = time.time() + 5.0
            while time.time() < deadline:
                if any(c.get("method") == "turn/start" for c in self.read_rpc_calls()):
                    break
                time.sleep(0.04)
            time.sleep(0.08)
            cancelled = self.core.cancel(t_cancel["id"])
            self.assertEqual(cancelled["status"], "CANCELLED")
            self.assertEqual(self.until_terminal(t_cancel["id"]), "CANCELLED")

        # 3. Reconnect cursor replay across pages
        full_page = self.core.events_page(t_err["id"], 0)
        self.assertTrue(len(full_page["events"]) >= 2)
        first_seq = full_page["events"][0]["seq"]
        page2 = self.core.events_page(t_err["id"], first_seq)
        all_seqs = [first_seq] + [e["seq"] for e in page2["events"]]
        self.assertEqual(all_seqs, [e["seq"] for e in full_page["events"]])
        self.assertFalse(page2["gap_detected"])


if __name__ == "__main__":
    unittest.main()
