#!/usr/bin/env python3
"""Clone pinned public source repos, then produce reproducible source inventories.

Does not execute any repository install/build scripts. Does not transmit or read local secrets.
Usage: python scripts/clone_audit.py [--only AntiGem Happier] [--refresh]
"""
from __future__ import annotations
import argparse
import collections
import datetime as dt
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'repos.lock.json').read_text(encoding='utf-8'))
SOURCE_DIR = ROOT / 'sources'
OUTPUT_DIR = ROOT / 'audit-output'
SOURCE_EXCLUDE = {'.git', 'node_modules', '.next', '.venv', 'dist', 'build', 'target', '.gradle', '.idea', 'vendor', 'coverage'}
CODE_SUFFIXES = {'.kt', '.kts', '.go', '.py', '.ts', '.tsx', '.js', '.jsx', '.rs', '.java', '.swift', '.json', '.yaml', '.yml', '.md'}
INTERESTING_SEGMENTS = {'agent', 'agents', 'session', 'sessions', 'runtime', 'bridge', 'codex', 'claude', 'preview', 'mcp', 'settings', 'providers', 'protocol', 'daemon', 'approvals', 'state', 'git', 'websocket'}

def cmd(args: list[str], cwd: Path | None = None, check: bool = True) -> str:
    proc = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and proc.returncode:
        raise RuntimeError(f"{' '.join(args)}: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout.strip()

def ensure_clone(entry: dict, refresh: bool) -> tuple[Path, str]:
    SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    root = SOURCE_DIR / entry['name']
    if not root.exists():
        print('Cloning', entry['repo'], flush=True)
        cmd(['git', 'clone', '--filter=blob:none', '--single-branch', '--branch', entry['branch'],
             'https://github.com/' + entry['repo'] + '.git', str(root)])
    elif not (root / '.git').exists():
        raise RuntimeError(f'{root} exists but is not a git checkout; refusing to overwrite')
    if refresh:
        cmd(['git', 'fetch', 'origin', entry['branch']], cwd=root)
    exists = cmd(['git', 'cat-file', '-e', entry['commit'] + '^{commit}'], cwd=root, check=False)
    # cat-file -e has no stdout, so verify via rev-parse instead.
    actual = cmd(['git', 'rev-parse', '--verify', entry['commit'] + '^{commit}'], cwd=root, check=False)
    if not actual:
        cmd(['git', 'fetch', '--depth=1', 'origin', entry['commit']], cwd=root)
    cmd(['git', 'checkout', '--detach', entry['commit']], cwd=root)
    head = cmd(['git', 'rev-parse', 'HEAD'], cwd=root)
    if head != entry['commit']:
        raise RuntimeError(f'Commit mismatch: expected {entry["commit"]}, got {head}')
    status = cmd(['git', 'status', '--porcelain'], cwd=root)
    if status:
        raise RuntimeError(f'Repository has local changes; refusing to audit: {root}')
    return root, head

def inventory(root: Path, entry: dict, head: str) -> dict:
    ext = collections.Counter()
    related: list[str] = []
    total = 0
    code_files = 0
    for file in root.rglob('*'):
        if not file.is_file() or any(s in SOURCE_EXCLUDE for s in file.relative_to(root).parts):
            continue
        rel = file.relative_to(root)
        total += 1
        ext[file.suffix.lower() or '<no-extension>'] += 1
        if file.suffix.lower() in CODE_SUFFIXES:
            code_files += 1
            if any(seg.lower() in INTERESTING_SEGMENTS for seg in rel.parts):
                related.append(rel.as_posix())
    return {
        'repository': entry['repo'], 'branch': entry['branch'], 'pinned_commit': head,
        'license_from_metadata': entry['license'], 'file_count': total, 'code_and_config_file_count': code_files,
        'extension_counts': dict(ext.most_common(30)), 'module_candidates': sorted(related)[:500],
        'audited_at_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'source_sha256_of_license_if_present': hashlib.sha256((root/'LICENSE').read_bytes()).hexdigest() if (root/'LICENSE').exists() else None
    }

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--only', nargs='+', help='Filter by logical names in repos.lock.json')
    parser.add_argument('--refresh', action='store_true', help='Fetch pinned updates if needed; never change pin')
    args = parser.parse_args()
    entries = [e for e in LOCK['repositories'] if not args.only or e['name'].lower() in {n.lower() for n in args.only}]
    if not entries:
        parser.error('No matching repositories')
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    succeeded = 0
    for e in entries:
        try:
            root, sha = ensure_clone(e, args.refresh)
            result = inventory(root, e, sha)
            (OUTPUT_DIR / f'{e["name"]}.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
            print(f'PASS {e["name"]}: {sha[:12]} {result["file_count"]} files')
            succeeded += 1
        except Exception as ex:
            print(f'FAIL {e["name"]}: {ex}', file=sys.stderr)
    print(f'Audit inventory: {succeeded}/{len(entries)} checked out and indexed')
    return 0 if succeeded == len(entries) else 1

if __name__ == '__main__':
    raise SystemExit(main())
