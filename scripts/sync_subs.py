#!/usr/bin/env python3
"""Sync BonoboSubs share subtitles into plain SRT files for the extension.

This script pulls embedded subtitles directly from the 1080p MKV files on the share.
It downloads the file locally to a temporary location to extract the subtitle track.

Outputs:
  subs/epNNN.srt          one SRT per episode
  subs/movie_XYZ.srt      one SRT per movie
  subs/.sync_state.json   source href/etag per output
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUBS_DIR = ROOT / "subs"
STATE_PATH = SUBS_DIR / ".sync_state.json"

BASE_URL = "https://bonobosubs.ovh"
SHARE_ROOT = "/public.php/dav/files/download"
MOVIE_DIR = f"{SHARE_ROOT}/1080p"

EPISODE_RE = re.compile(r"(?:Episode\s*|Xian\s*Ni\s*-\s*)(\d{1,4})", re.IGNORECASE)
ASS_TIME_RE = re.compile(r"(\d+):(\d{1,2}):(\d{1,2})\.(\d{1,2})")
ASS_TAG_RE = re.compile(r"\{[^}]*\}")
HTML_TAG_RE = re.compile(r"<[^>]*>")

DAV_NS = "{DAV:}"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
PROPFIND_BODY = (
    b'<?xml version="1.0"?>'
    b'<d:propfind xmlns:d="DAV:"><d:prop>'
    b"<d:resourcetype/><d:getcontentlength/><d:getetag/>"
    b"</d:prop></d:propfind>"
)


def fetch_url_with_retry(request: urllib.request.Request) -> bytes:
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return response.read()
        except Exception:
            if attempt == 2:
                raise
            time.sleep(5)


def propfind(path: str) -> list[dict]:
    request = urllib.request.Request(BASE_URL + path, data=PROPFIND_BODY, method="PROPFIND")
    request.add_header("Depth", "1")
    request.add_header("User-Agent", USER_AGENT)
    body = fetch_url_with_retry(request)
    root = ET.fromstring(body)
    entries = []
    for response in root.findall(f"{DAV_NS}response"):
        href = response.findtext(f"{DAV_NS}href")
        if not href:
            continue
        prop = response.find(f"{DAV_NS}propstat/{DAV_NS}prop")
        collection = prop is not None and prop.find(f"{DAV_NS}resourcetype/{DAV_NS}collection") is not None
        etag = response.findtext(f"{DAV_NS}propstat/{DAV_NS}prop/{DAV_NS}getetag")
        entries.append({
            "href": href,
            "name": urllib.parse.unquote(href.rstrip("/").rsplit("/", 1)[-1]),
            "collection": collection,
            "etag": etag.strip('"') if etag else None,
        })
    return entries


def parse_ass_time(value: str) -> float | None:
    match = ASS_TIME_RE.fullmatch(value.strip())
    if not match:
        return None
    hours, minutes, seconds, fraction = match.groups()
    centis = int(fraction.ljust(2, "0")[:2])
    return int(hours) * 3600 + int(minutes) * 60 + int(seconds) + centis / 100.0


def format_srt_time(seconds: float) -> str:
    total_ms = int(round(seconds * 1000))
    hours, rest = divmod(total_ms, 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    secs, millis = divmod(rest, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def clean_ass_text(text: str) -> str:
    text = ASS_TAG_RE.sub("", text)
    text = text.replace("\\h", " ")
    text = text.replace("\\N", "\n").replace("\\n", "\n")
    text = HTML_TAG_RE.sub("", text)
    return text.strip()


def ass_to_srt(ass_text: str) -> str:
    events: list[tuple[float, int, float, str]] = []
    in_events = False
    order = 0
    for raw_line in ass_text.splitlines():
        line = raw_line.strip()
        if line.startswith("["):
            in_events = line.lower() == "[events]"
            continue
        if not in_events or not line.startswith("Dialogue:"):
            continue
        parts = line[len("Dialogue:"):].split(",", 9)
        if len(parts) != 10:
            continue
        start = parse_ass_time(parts[1])
        end = parse_ass_time(parts[2])
        if start is None or end is None or end <= start:
            continue
        text = clean_ass_text(parts[9])
        if not text:
            continue
        events.append((start, order, end, text))
        order += 1

    events.sort(key=lambda event: (event[0], event[1]))
    blocks = []
    for index, (start, _, end, text) in enumerate(events, 1):
        blocks.append(
            f"{index}\n{format_srt_time(start)} --> {format_srt_time(end)}\n{text}\n"
        )
    return "\n".join(blocks)


def find_ffmpeg() -> str | None:
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def load_state() -> dict:
    if STATE_PATH.exists():
        try:
            return json.loads(STATE_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
    return {}


def write_subs(name: str, srt: str, changed: list[str]) -> bool:
    destination = SUBS_DIR / f"{name}.srt"
    if destination.exists() and destination.read_text(encoding="utf-8") == srt:
        return False
    destination.write_text(srt, encoding="utf-8", newline="")
    changed.append(name)
    return True


def sync_embedded(state: dict, force: bool, changed: list[str]) -> None:
    ffmpeg = find_ffmpeg()
    if ffmpeg is None:
        raise RuntimeError("ffmpeg not found")

    for entry in propfind(MOVIE_DIR):
        if entry["collection"] or not entry["name"].lower().endswith(".mkv"):
            continue
            
        match = EPISODE_RE.search(entry["name"])
        if match:
            episode = int(match.group(1))
            key = f"ep{episode:03d}"
        else:
            name_no_ext = Path(entry["name"]).stem
            safe_name = re.sub(r'[^a-zA-Z0-9]', '_', name_no_ext).strip('_')
            key = f"movie_{safe_name}"

        destination = SUBS_DIR / f"{key}.srt"
        record = state.get(key, {})
        
        # Always use embedded subtitles. If we already have the right file and etag, skip.
        if not force and destination.exists() and record.get("source") == "embedded":
            if record.get("href") == entry["href"] and record.get("etag") == entry["etag"]:
                continue

        print(f"Downloading {key} video to extract subtitle...")
        with tempfile.TemporaryDirectory() as work_dir:
            mkv_path = Path(work_dir) / "vid.mkv"
            ass_path = Path(work_dir) / "sub.ass"
            
            try:
                req = urllib.request.Request(BASE_URL + entry["href"], headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req) as response:
                    with open(mkv_path, "wb") as out_file:
                        downloaded = 0
                        while True:
                            chunk = response.read(1024 * 1024 * 5)
                            if not chunk:
                                break
                            out_file.write(chunk)
                            downloaded += len(chunk)
                            if downloaded % (50 * 1024 * 1024) == 0:
                                print(".", end="", flush=True)
                print(" Download complete! Extracting...")
            except Exception as e:
                print(f"\n{key}: failed to download video ({e})", file=sys.stderr)
                continue

            command = [
                ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", str(mkv_path),
                "-map", "0:s:0", "-c:s", "copy", "-f", "ass", str(ass_path),
            ]
            result = subprocess.run(command, capture_output=True)
            if result.returncode != 0 or not ass_path.exists():
                print(f"{key}: ffmpeg failed to extract", file=sys.stderr)
                continue
            
            try:
                ass_text = ass_path.read_text(encoding="utf-8-sig", errors="replace")
                srt = ass_to_srt(ass_text)
            except Exception as error:
                print(f"{key}: failed to parse embedded sub ({error})", file=sys.stderr)
                continue
            
            if not srt.strip():
                print(f"{key}: no subtitle cues found", file=sys.stderr)
                continue
            
            state[key] = {
                "source": "embedded", 
                "file": entry["name"], 
                "href": entry["href"], 
                "etag": entry["etag"]
            }
            if write_subs(key, srt, changed):
                print(f"{key}: updated from embedded subtitle track")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    SUBS_DIR.mkdir(parents=True, exist_ok=True)
    state = load_state()
    
    # We will reset states that were NOT from "embedded" source ONLY for recent episodes > 140
    keys_to_remove = []
    for k, v in state.items():
        if k.startswith("ep"):
            try:
                ep_num = int(k[2:])
                if ep_num >= 145 and v.get("source") != "embedded":
                    keys_to_remove.append(k)
                    dest = SUBS_DIR / f"{k}.srt"
                    if dest.exists():
                        dest.unlink()
            except ValueError:
                pass
    
    for k in keys_to_remove:
        del state[k]

    changed: list[str] = []

    sync_embedded(state, args.force, changed)

    STATE_PATH.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{len(changed)} subtitle file(s) changed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
