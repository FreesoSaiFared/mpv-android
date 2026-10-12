#!/usr/bin/env python3
"""Align phone audio clocks with i9ES central YouTube clip playback."""
import argparse, bisect, json, sqlite3, statistics
from pathlib import Path

ROOT=Path(__file__).resolve().parent
DATA=ROOT/"state"

def read_events(path):
    records=[]
    for line in path.read_text().splitlines():
        try: records.append(json.loads(line))
        except ValueError: continue
    return records
def phone_clock(events):
    points=[]
    for x in events:
        if x.get("schema")!="morning-show-hub-sync/1": continue
        state=x.get("server_state",{})
        if not state.get("server_now_ns"): continue
        a=x["client_send_mono_ns"]; b=x["client_receive_mono_ns"]
        points.append((b-a, state["server_now_ns"]-(a+b)//2))
    if not points: return None
    points.sort()
    fastest=points[:max(1,len(points)//2)]
    return {"offset_ns":int(statistics.median(t for _,t in fastest)),
            "best_roundtrip_ms":min(x for x,_ in points)/1e6,
            "samples":len(points)}
def master_events(db,session):
    rows=db.execute("select data from events where session=? and kind='playback' order by received_ns",(session,))
    frames=[]
    for (data,) in rows:
        try:
            x=json.loads(data)
            if x.get("clip_id") and "updated_ns" in x:
                frames.append(x)
        except ValueError: continue
    return frames

def position_at(frames,server_ns):
    if not frames:return None
    times=[x["updated_ns"] for x in frames]
    index=bisect.bisect_right(times,server_ns)-1
    if index<0:return None
    x=frames[index]
    elapsed=max(0,server_ns-x["updated_ns"])/1e9
    projected=x["position_sec"]+(0 if x["paused"] else elapsed*x.get("rate",1))
    return {"clip_id":x["clip_id"],"position_sec":round(projected,3),
            "paused":x["paused"],"master_event_ns":x["updated_ns"]}
def reconcile(session):
    db=sqlite3.connect(DATA/"studio.db")
    frames=master_events(db,session)
    result={"schema":"morning-show-reconciled/1","session":session,
            "master_events":len(frames),"audio_tracks":[]}
    directory=DATA/"recordings"/session
    for path in sorted(directory.glob("*/*.events.jsonl")):
        items=read_events(path); clock=phone_clock(items)
        if clock is None:continue
        started=next((e["elapsed_realtime_nanos"] for e in items
            if e.get("event")=="recording_started"),None)
        anchors=[]
        for e in items:
            if e.get("schema")!="morning-show-hub-sync/1":continue
            midpoint=(e["client_send_mono_ns"]+e["client_receive_mono_ns"])//2
            ts=midpoint+clock["offset_ns"]
            pos=position_at(frames,ts)
            if pos: anchors.append(dict(audio_sec=round((midpoint-started)/1e9,3)
                if started else None,master=pos))
        audio=path.with_name(path.name.replace(".events.jsonl",".m4a"))
        result["audio_tracks"].append({
            "speaker":path.parent.name,"audio_file":str(audio),
            "event_file":str(path),"recording_start_mono_ns":started,
            "phone_to_server_clock":clock,
            "server_start_ns":started+clock["offset_ns"] if started else None,
            "anchors":anchors})
    return result

if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("session")
    args=ap.parse_args()
    result=reconcile(args.session)
    out=DATA/"exports"/(args.session+".json")
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(result,indent=2))
    print(json.dumps({"output":str(out),"tracks":len(result["audio_tracks"]),
        "master_events":result["master_events"]}))
