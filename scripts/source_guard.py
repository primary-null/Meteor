"""Scan exact tracked/index blobs without printing possible credential values."""
import argparse,json,re,subprocess
from pathlib import Path,PurePosixPath
ROOT=Path(__file__).resolve().parents[1]
PATTERNS=[re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),re.compile(rb"\bgh[pousr]_[A-Za-z0-9_]{30,}\b"),re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{30,}\b")]
DIRS={".git",".gradle",".idea",".kotlin",".worktrees",".backups","__pycache__","build","captures"}
SUFFIXES={".apk",".aab",".apks",".jks",".keystore",".dpapi",".pem",".p12",".pfx",".key",".bundle",".zip"}
def violation(name,data,mode="100644"):
    path=PurePosixPath(name)
    if mode not in ("100644","100755"):return "unsupported file type"
    if any(part in DIRS for part in path.parts):return "generated/private directory"
    if path.name=="local.properties" or path.name==".env" or path.name.startswith(".env.") or "private" in path.name.lower():return "private runtime file"
    if path.suffix.lower() in SUFFIXES:return "credential/build/backup artifact"
    if len(data)>25*1024*1024:return "source file exceeds 25 MiB"
    if any(pattern.search(data) for pattern in PATTERNS):return "possible credential material"
def git(*args):
    return subprocess.run(["git",*args],cwd=ROOT,capture_output=True,check=True).stdout
def snapshot(staged=False,ref=None):
    rows=[]
    records=git("ls-files","--stage","-z") if staged else git("ls-tree","-r","-z",ref)
    for record in records.split(b"\0"):
        if not record:continue
        meta,path=record.split(b"\t",1);parts=meta.decode().split()
        if staged:
            mode,oid,stage=parts
            if stage!="0":raise ValueError("Unresolved index")
        else:
            mode,kind,oid=parts
            if kind!="blob":raise ValueError("Unreviewed submodule")
        rows.append((path.decode(),mode,oid))
    ids=list(dict.fromkeys(oid for _,_,oid in rows))
    raw=subprocess.run(["git","cat-file","--batch"],cwd=ROOT,input=("".join(x+"\n" for x in ids)).encode(),capture_output=True,check=True).stdout
    offset=0;blobs={}
    for oid in ids:
        end=raw.index(b"\n",offset);head=raw[offset:end].split();size=int(head[2])
        assert head[0].decode()==oid and head[1]==b"blob"
        blobs[oid]=raw[end+1:end+1+size];offset=end+2+size
    return [(name,mode,blobs[oid]) for name,mode,oid in rows]
def main():
    p=argparse.ArgumentParser();g=p.add_mutually_exclusive_group();g.add_argument("--staged",action="store_true");g.add_argument("--ref");a=p.parse_args()
    if a.staged or a.ref:files=snapshot(a.staged,a.ref)
    else:
        names=git("ls-files","--cached","--others","--exclude-standard","-z").decode().split("\0");files=[]
        for name in dict.fromkeys(n for n in names if n):
            path=ROOT/name
            if not path.exists():raise ValueError("Tracked file missing: "+name)
            files.append((name,"120000" if path.is_symlink() else "100644",path.read_bytes()))
    failures=[{"path":n,"reason":r} for n,m,d in files if (r:=violation(n,d,m))]
    print(json.dumps({"sourceGuard":"PASS" if not failures else "FAIL","files":len(files),"failures":failures}))
    if failures:raise SystemExit(1)
if __name__=="__main__":main()
