#!/usr/bin/env python3
"""Morning Show central playback clock, clip queue and audio archive."""
import argparse, hashlib, json, os, re, secrets, sqlite3, subprocess, time
from pathlib import Path
from threading import RLock
from urllib.parse import urlparse, parse_qs
from flask import Flask, Response, request, jsonify, send_file

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "state"
DATA.mkdir(exist_ok=True)
KEYFILE = DATA / "token"
if not KEYFILE.exists():
    KEYFILE.write_text(secrets.token_urlsafe(32))
    KEYFILE.chmod(0o600)
TOKEN = KEYFILE.read_text().strip()
DB = sqlite3.connect(DATA / "studio.db", check_same_thread=False)
DB.execute("pragma journal_mode=wal")
DB.execute("create table if not exists clips (id text primary key, video text, start real, end real, title text, created real)")
DB.execute("create table if not exists events (id integer primary key, session text, source text, kind text, data text, received_ns integer)")
DB.commit()
LOCK = RLock()
app = Flask(__name__)
CURRENT = {"session":"morning", "clip_id":None, "position_sec":0.0,"paused":True,"rate":1.0,"updated_ns":time.time_ns()}
@app.before_request
def access():
    if request.path in ("/health", "/studio") or request.method == "OPTIONS":
        return None
    if request.headers.get("Authorization") != "Bearer " + TOKEN:
        return jsonify(error="invalid_authorization"), 401

@app.after_request
def cors(r):
    r.headers["Access-Control-Allow-Origin"] = "*"
    r.headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS"
    r.headers["Access-Control-Allow-Headers"] = "Authorization,Content-Type"
    r.headers["Cache-Control"] = "no-store"
    return r

@app.route("/health")
def health():
    return jsonify(ok=True, service="morning-show-central", ts_ns=time.time_ns())
@app.route("/api/time", methods=["GET","POST"])
def clock():
    return jsonify(server_wall_ns=time.time_ns(),
        client_mono_ns=request.args.get("t0"), session=CURRENT["session"])

def youtube_id(url):
    u = urlparse(url)
    host = (u.hostname or "").lower()
    if host in ("youtu.be", "www.youtu.be"):
        candidate = u.path.strip("/").split("/")[0]
    elif host in ("youtube.com", "www.youtube.com", "m.youtube.com"):
        candidate = parse_qs(u.query).get("v", [""])[0] if u.path == "/watch" else u.path.strip("/").split("/")[-1]
    else:
        return None
    return candidate if re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate) else None
@app.route("/api/clips", methods=["GET","POST"])
def clips():
    if request.method == "POST":
        body = request.get_json(silent=True) or {}
        video = youtube_id(str(body.get("url","")))
        if not video: return jsonify(error="invalid_youtube_url"), 400
        try:
            start, end = float(body.get("start",0)),float(body.get("end",60))
        except (TypeError,ValueError): return jsonify(error="invalid_range"),400
        if not (0 <= start < end <= 86400 and end-start <= 3600):
            return jsonify(error="invalid_range"),400
        ident = secrets.token_hex(8)
        title = str(body.get("title","Untitled clip"))[:180]
        with LOCK:
            DB.execute("insert into clips values(?,?,?,?,?,?)",
                (ident,video,start,end,title,time.time()))
            DB.commit()
        return jsonify(id=ident,video=video,start=start,end=end,title=title),201
    with LOCK:
        rows = DB.execute("select id,video,start,end,title from clips order by created").fetchall()
    return jsonify(clips=[dict(zip(("id","video","start","end","title"),r)) for r in rows])
@app.route("/api/playback",methods=["GET","POST"])
def playback():
    if request.method == "POST":
        body = request.get_json(silent=True) or {}
        clip_id = body.get("clip_id")
        with LOCK:
            if clip_id and not DB.execute("select 1 from clips where id=?",(clip_id,)).fetchone():
                return jsonify(error="unknown_clip"),400
            CURRENT.update(session=str(body.get("session","morning"))[:70],
                clip_id=clip_id,position_sec=float(body.get("position_sec",0)),
                paused=bool(body.get("paused",True)),
                rate=float(body.get("rate",1)),updated_ns=time.time_ns())
            DB.execute("insert into events(session,source,kind,data,received_ns) values(?,?,?,?,?)",
                (CURRENT["session"],"chromebook","playback",json.dumps(CURRENT),time.time_ns()))
            DB.commit()
    return jsonify(dict(CURRENT, server_now_ns=time.time_ns()))

@app.route("/api/events",methods=["POST"])
def events():
    body = request.get_json(silent=True) or {}
    session = str(body.get("session","morning"))[:70]
    source = str(body.get("source","phone"))[:80]
    kind = str(body.get("kind","sync_anchor"))[:80]
    received = time.time_ns()
    with LOCK:
        DB.execute("insert into events(session,source,kind,data,received_ns) values(?,?,?,?,?)",
            (session,source,kind,json.dumps(body),received))
        DB.commit()
    return jsonify(ok=True,received_ns=received,playback=CURRENT)
@app.route("/api/recordings/<session>/<speaker>/<filename>",methods=["POST"])
def upload(session,speaker,filename):
    if not all(re.fullmatch(r"[A-Za-z0-9_.-]{1,100}",s) and ".." not in s
            for s in (session,speaker,filename)):
        return jsonify(error="invalid_name"),400
    if not filename.endswith((".m4a",".jsonl",".json")):
        return jsonify(error="invalid_extension"),400
    path = DATA / "recordings" / session / speaker
    path.mkdir(parents=True,exist_ok=True)
    dest = path / filename
    if dest.exists(): return jsonify(error="file_exists"),409
    temp = path / (filename + ".part")
    h = hashlib.sha256()
    total = 0
    with temp.open("xb") as f:
        while chunk := request.stream.read(65536):
            total += len(chunk)
            if total > 300_000_000:
                f.close(); temp.unlink(missing_ok=True)
                return jsonify(error="upload_too_large"),413
            h.update(chunk); f.write(chunk)
    temp.rename(dest)
    return jsonify(ok=True,bytes=total,sha256=h.hexdigest(),file=str(dest)),201
@app.route("/api/playlist.m3u")
def playlist():
    with LOCK:
        clips = DB.execute("select video,start,end,title from clips order by created").fetchall()
    lines = ["#EXTM3U","#PLAYLIST:Morning Show"]
    for video,start,end,title in clips:
        lines.extend([f"#EXTINF:{int(end-start)},{title}",
            f"#EXTVLCOPT:start-time={start:g}",f"#EXTVLCOPT:stop-time={end:g}",
            f"https://www.youtube.com/watch?v={video}"])
    return Response("\n".join(lines)+"\n", mimetype="audio/x-mpegurl")

@app.route("/api/mpv/<ident>")
def mpv_command(ident):
    with LOCK:
        row = DB.execute("select video,start,end from clips where id=?",(ident,)).fetchone()
    if not row: return jsonify(error="not_found"),404
    video,start,end = row
    return jsonify(command=["mpv","--ytdl=yes",f"--start={start:g}",
        f"--end={end:g}",f"https://www.youtube.com/watch?v={video}"],
        note="Run mpv with yt-dlp on the chosen viewing host")
@app.route("/studio")
def studio():
    return send_file(ROOT / "studio.html")
if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host",default="127.0.0.1")
    ap.add_argument("--port",type=int,default=18767)
    args = ap.parse_args()
    print("Morning Show listening on",args.host,args.port,flush=True)
    app.run(host=args.host,port=args.port,threaded=True,use_reloader=False)
