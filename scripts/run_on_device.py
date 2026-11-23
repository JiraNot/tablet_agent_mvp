import subprocess
import urllib.request
import json
import time

def adb_forward():
    subprocess.run(["adb", "forward", "tcp:18765", "tcp:8765"], capture_output=True)

def get_token():
    res = subprocess.run(
        ["adb", "shell", "run-as com.tabletagent.local cat files/runtime/rootfs/root/tablet-agent/device_token"],
        capture_output=True, text=True
    )
    return res.stdout.strip().splitlines()[-1].strip()

def bridge_request(endpoint, method="GET", body=None, token=None):
    adb_forward()
    if token is None:
        token = get_token()
    url = f"http://127.0.0.1:18765{endpoint}"
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode("utf-8")
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        return {"error": str(e)}

def run_term_cmd(project, cmd, wait_sec=4.0):
    token = get_token()
    poll = bridge_request(f"/v1/projects/{project}/terminal/poll?after=0", token=token)
    seq = poll.get("seq", 0)
    bridge_request(f"/v1/projects/{project}/terminal/input", method="POST", body={"data": f"{cmd}\n"}, token=token)
    time.sleep(wait_sec)
    poll = bridge_request(f"/v1/projects/{project}/terminal/poll?after={seq}", token=token)
    print("TERM DATA:\n" + poll.get("data", ""))

if __name__ == "__main__":
    cmd = "mkdir -p /root/models && curl -ILs https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf | grep -iE 'content-length|location'"
    run_term_cmd("tablet_agent_mvp", cmd, wait_sec=3.0)
