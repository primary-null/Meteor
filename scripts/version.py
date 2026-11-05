"""Meteor product label and monotonic Android delivery number."""
import argparse,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def parse(text):
    values={}
    for line in text.splitlines():
        line=line.strip()
        if not line or line.startswith("#"):continue
        key,value=line.split("=",1)
        if key in values:raise ValueError("Duplicate version property")
        values[key]=value
    if set(values)!={"versionName","versionCode"}:raise ValueError("Expected versionName/versionCode")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?",values["versionName"]):raise ValueError("Invalid product version")
    if not values["versionCode"].isdigit() or not 0<int(values["versionCode"])<2100000000:raise ValueError("Invalid Android versionCode")
    return values["versionName"],int(values["versionCode"])
def advance(name,code,kind):
    parts=[int(x) for x in name.split(".")]
    if len(parts)==2:parts.append(0)
    if kind=="patch":name=f"{parts[0]}.{parts[1]}.{parts[2]+1}"
    elif kind=="minor":name=f"{parts[0]}.{parts[1]+1}"
    elif kind=="major":name=f"{parts[0]+1}.0"
    elif kind!="build":raise ValueError("Unknown bump")
    parse(f"versionName={name}\nversionCode={code+1}")
    return name,code+1
def main():
    p=argparse.ArgumentParser();p.add_argument("command",choices=["show","bump"]);p.add_argument("kind",nargs="?",choices=["patch","minor","major","build"]);p.add_argument("--staged",action="store_true");a=p.parse_args()
    text=subprocess.run(["git","show",":version.properties"],cwd=ROOT,capture_output=True,text=True,check=True).stdout if a.staged else (ROOT/"version.properties").read_text()
    name,code=parse(text)
    if a.command=="bump":
        if not a.kind or a.staged:p.error("bump requires kind, without --staged")
        name,code=advance(name,code,a.kind)
        (ROOT/"version.properties").write_text(f"# Product label and monotonically increasing Android delivery number.\nversionName={name}\nversionCode={code}\n")
    print(json.dumps({"versionName":name,"versionCode":code}))
if __name__=="__main__":main()
