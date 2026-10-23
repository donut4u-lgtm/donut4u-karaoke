import os
import re
import json
import time
import html
import shutil
import secrets
import threading
import subprocess
import ipaddress
import socket
import tempfile

from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urlparse, parse_qs, urljoin

import httpx

from fastapi import FastAPI, BackgroundTasks, HTTPException, Query
from fastapi.responses import FileResponse
from faster_whisper import WhisperModel


app = FastAPI(
    title="Donut4U Karaoke Processing API",
    version="2.2.4",
)

JOB_TTL_MINUTES = int(os.getenv("JOB_TTL_MINUTES", "60"))
MAX_TRACK_SECONDS = int(os.getenv("MAX_TRACK_SECONDS", "600"))
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "tiny")
DEMUCS_MODEL = os.getenv("DEMUCS_MODEL", "htdemucs")
DEMUCS_SEGMENT_SECONDS = os.getenv("DEMUCS_SEGMENT_SECONDS", "7.8")

JOB_ROOT = Path(os.getenv("JOB_ROOT", "/tmp/donut4u-jobs"))
JOBS_FILE = JOB_ROOT / "jobs.json"

MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
MAX_REDIRECTS = 5

SUPPORTED_AUDIO_EXTENSIONS = (
    ".mp3", ".wav", ".m4a", ".aac",
    ".ogg", ".opus", ".flac",
)

AUDIO_CONTENT_TYPES = {
    "audio/mpeg", "audio/mp3", "audio/wav",
    "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/mp4", "audio/x-m4a", "audio/aac",
    "audio/ogg", "audio/opus", "audio/flac",
    "audio/x-flac", "application/octet-stream",
}

for variable in (
    "OMP_NUM_THREADS",
    "MKL_NUM_THREADS",
    "OPENBLAS_NUM_THREADS",
    "NUMEXPR_NUM_THREADS",
):
    os.environ.setdefault(variable, "1")

JOB_ROOT.mkdir(parents=True, exist_ok=True)

JOBS = {}
JOBS_LOCK = threading.RLock()
PROCESSING_LOCK = threading.Lock()
WHISPER_LOCK = threading.Lock()
WHISPER_INSTANCE = None


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def save_jobs_locked():
    JOB_ROOT.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(
        prefix="jobs-", suffix=".tmp", dir=str(JOB_ROOT)
    )

    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(JOBS, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_name, JOBS_FILE)
    finally:
        if os.path.exists(temp_name):
            try:
                os.remove(temp_name)
            except OSError:
                pass


def load_jobs():
    if not JOBS_FILE.is_file():
        return

    try:
        with open(JOBS_FILE, "r", encoding="utf-8") as source:
            data = json.load(source)

        if not isinstance(data, dict):
            return

        now = time.time()

        with JOBS_LOCK:
            for job_id, job in data.items():
                if not isinstance(job, dict):
                    continue

                directory = Path(
                    job.get("directory", str(JOB_ROOT / job_id))
                )

                try:
                    directory.resolve().relative_to(JOB_ROOT.resolve())
                except (ValueError, OSError):
                    continue

                job["directory"] = str(directory)

                if job.get("status") in ("queued", "processing"):
                    job["status"] = "failed"
                    job["progress"] = "failed"
                    job["error"] = (
                        "The server restarted during processing. "
                        "Please submit the track again."
                    )
                    job["updated_at"] = utc_now()
                    job["updated_at_epoch"] = now

                JOBS[job_id] = job

            save_jobs_locked()

    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return


def cleanup_expired_jobs():
    cutoff = time.time() - JOB_TTL_MINUTES * 60
    expired = []

    with JOBS_LOCK:
        for job_id, job in list(JOBS.items()):
            if job.get("status") in ("queued", "processing"):
                continue

            if job.get("updated_at_epoch", time.time()) < cutoff:
                expired.append(job.get("directory"))
                del JOBS[job_id]

        if expired:
            save_jobs_locked()

    for directory in expired:
        if directory:
            shutil.rmtree(directory, ignore_errors=True)


def get_job_or_404(job_id):
    cleanup_expired_jobs()
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            raise HTTPException(
                status_code=404,
                detail="Job not found or expired.",
            )
        return dict(job)


def update_job(job_id, **changes):
    with JOBS_LOCK:
        if job_id not in JOBS:
            return
        JOBS[job_id].update(changes)
        JOBS[job_id]["updated_at"] = utc_now()
        JOBS[job_id]["updated_at_epoch"] = time.time()
        save_jobs_locked()


# ============================================================
# URL VALIDATION
# ============================================================

def validate_public_url(url):
    url = (url or "").strip()

    try:
        parsed = urlparse(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Invalid URL.") from exc

    if (
        parsed.scheme not in ("http", "https")
        or not host
        or parsed.username
        or parsed.password
    ):
        raise ValueError("Use a complete public HTTP or HTTPS URL.")

    host = host.lower().rstrip(".")

    if host in ("localhost", "localhost.localdomain") or host.endswith(".local"):
        raise ValueError("Local network URLs are not supported.")

    try:
        addresses = socket.getaddrinfo(
            host,
            port or (443 if parsed.scheme == "https" else 80),
            type=socket.SOCK_STREAM,
        )
    except (socket.gaierror, OSError, ValueError) as exc:
        raise ValueError("The URL host could not be resolved.") from exc

    if not addresses:
        raise ValueError("The URL host could not be resolved.")

    for item in addresses:
        try:
            address = ipaddress.ip_address(item[4][0])
        except ValueError as exc:
            raise ValueError("Invalid host address.") from exc

        if not address.is_global:
            raise ValueError("Private or local network URLs are not supported.")

    return url


def is_direct_audio_url(url):
    try:
        parsed = urlparse(url or "")
        return (
            parsed.scheme in ("http", "https")
            and bool(parsed.hostname)
            and parsed.path.lower().endswith(SUPPORTED_AUDIO_EXTENSIONS)
        )
    except (TypeError, ValueError):
        return False


def validate_audio_url(url):
    url = validate_public_url(url)
    if not is_direct_audio_url(url):
        raise ValueError(
            "Only direct MP3, WAV, M4A, AAC, OGG, OPUS, or FLAC "
            "audio-file URLs are supported. Webpages and video links "
            "are not supported."
        )
    return url


# ============================================================
# AUDIO SEARCH
# ============================================================

def web_search(query):
    query = (query or "").strip()
    if not query:
        return []

    search_query = (
        f"{query} "
        "(filetype:mp3 OR filetype:wav OR filetype:m4a "
        "OR filetype:aac OR filetype:ogg OR filetype:flac)"
    )

    try:
        response = httpx.get(
            "https://www.google.com/search",
            params={"q": search_query, "num": 30, "gbv": "1"},
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (X11; Linux x86_64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/131.0.0.0 Safari/537.36"
                ),
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "en-US,en;q=0.9",
            },
            timeout=httpx.Timeout(15.0, connect=5.0),
            follow_redirects=True,
        )
        response.raise_for_status()
    except httpx.HTTPError as exc:
        raise HTTPException(
            status_code=502,
            detail="Audio search is temporarily unavailable.",
        ) from exc

    page = response.text
    page_lower = page.lower()
    final_url = str(response.url).lower()

    if (
        "unusual traffic" in page_lower
        or "automated queries" in page_lower
        or "/sorry/" in final_url
    ):
        raise HTTPException(
            status_code=502,
            detail="Search is temporarily restricted. Try again later.",
        )

    results = []
    seen = set()

    for match in re.finditer(
        r"<a\b([^>]*)>(.*?)</a\s*>", page, re.I | re.S
    ):
        attributes, body = match.groups()
        heading_match = re.search(
            r"<h3\b[^>]*>(.*?)</h3\s*>", body, re.I | re.S
        )
        if not heading_match:
            continue

        href_match = re.search(
            r"\bhref\s*=\s*([\"'])(.*?)\1", attributes, re.I | re.S
        )
        if not href_match:
            continue

        target = html.unescape(href_match.group(2)).strip()

        if target.startswith("//"):
            target = "https:" + target
        elif target.startswith("/"):
            target = "https://www.google.com" + target

        try:
            parsed = urlparse(target)
        except ValueError:
            continue

        host = (parsed.hostname or "").lower()
        if host == "google.com" or host.endswith(".google.com"):
            if parsed.path == "/url":
                params = parse_qs(parsed.query)
                target = (params.get("q") or params.get("url") or [""])[0]
            else:
                continue

        target = html.unescape(target).strip()
        if not is_direct_audio_url(target):
            continue

        try:
            target = validate_audio_url(target)
        except ValueError:
            continue

        if target in seen:
            continue

        heading = heading_match.group(1)
        title = html.unescape(
            re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", heading))
        ).strip()
        if not title:
            continue

        seen.add(target)
        results.append({
            "title": title[:250],
            "name": title[:250],
            "url": target,
            "page_url": target,
            "id": target,
            "artist": "",
            "snippet": "",
            "source": urlparse(target).hostname or "",
            "duration": 0,
            "preview": False,
        })

        if len(results) >= 20:
            break

    return results


def resolve_audio_url(source_url):
    return validate_audio_url(source_url)


# ============================================================
# AUDIO DOWNLOAD
# ============================================================

def download_track(url, destination):
    current_url = validate_audio_url(url)
    total = 0

    def has_audio_signature(data):
        if not data:
            return False
        if data.startswith((b"ID3", b"fLaC", b"OggS")):
            return True
        if len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xE0) == 0xE0:
            return True
        if len(data) >= 12 and data.startswith(b"RIFF") and data[8:12] == b"WAVE":
            return True
        if len(data) >= 12 and data[4:8] == b"ftyp":
            return True
        if len(data) >= 2 and data[0] == 0xFF and (data[1] & 0xF6) == 0xF0:
            return True
        return False

    for redirect_count in range(MAX_REDIRECTS + 1):
        with httpx.stream(
            "GET",
            current_url,
            follow_redirects=False,
            timeout=httpx.Timeout(120.0, connect=10.0),
            headers={
                "User-Agent": "Donut4U-Karaoke/2.2.4",
                "Accept": "audio/*,application/octet-stream,*/*",
            },
        ) as response:
            if response.is_redirect:
                if redirect_count >= MAX_REDIRECTS:
                    raise ValueError("Audio URL exceeded the maximum number of redirects.")

                location = response.headers.get("location")
                if not location:
                    raise ValueError("Audio redirect did not provide a destination.")

                current_url = validate_public_url(urljoin(current_url, location))
                continue

            response.raise_for_status()
            content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    size = int(content_length)
                except (TypeError, ValueError):
                    size = None
                if size is not None and size > MAX_DOWNLOAD_BYTES:
                    raise ValueError("Audio file exceeds the 100 MB limit.")

            chunks = response.iter_bytes(1024 * 1024)
            first_chunk = next(chunks, b"")
            if not first_chunk:
                raise ValueError("The downloaded audio file is empty.")

            if first_chunk.lstrip().lower().startswith(
                (b"<!doctype html", b"<html", b"<head", b"<body")
            ):
                raise ValueError("The URL returned a webpage instead of an audio file.")

            if content_type not in AUDIO_CONTENT_TYPES and not has_audio_signature(first_chunk[:64]):
                raise ValueError(
                    "The server response is not recognized as an audio file. "
                    "Try another direct audio-file URL."
                )

            with open(destination, "wb") as output:
                total = len(first_chunk)
                if total > MAX_DOWNLOAD_BYTES:
                    raise ValueError("Audio file exceeds the 100 MB limit.")
                output.write(first_chunk)

                for chunk in chunks:
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > MAX_DOWNLOAD_BYTES:
                        raise ValueError("Audio file exceeds the 100 MB limit.")
                    output.write(chunk)
            break

    if total == 0:
        raise ValueError("The downloaded audio file is empty.")


# ============================================================
# AUDIO DURATION AND WHISPER
# ============================================================

def get_audio_duration(path):
    result = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        capture_output=True,
        text=True,
        timeout=30,
        check=True,
    )
    duration = float(result.stdout.strip())
    if duration <= 0 or duration > MAX_TRACK_SECONDS:
        raise ValueError(
            f"Audio must be between 1 second and {MAX_TRACK_SECONDS} seconds."
        )
    return duration


def get_whisper_model():
    global WHISPER_INSTANCE
    with WHISPER_LOCK:
        if WHISPER_INSTANCE is None:
            WHISPER_INSTANCE = WhisperModel(
                WHISPER_MODEL, device="cpu", compute_type="int8"
            )
        return WHISPER_INSTANCE


# ============================================================
# KARAOKE PROCESSING
# ============================================================

def process_job(job_id):
    # Only one Demucs/Whisper job runs at a time in this process.
    with PROCESSING_LOCK:
        try:
            job = get_job_or_404(job_id)
        except HTTPException:
            return

        directory = Path(job["directory"])
        source_path = directory / "source-audio"
        output_root = directory / "separated"
        instrumental_path = directory / "instrumental.wav"
        lyrics_path = directory / "lyrics.json"

        try:
            update_job(
                job_id,
                status="processing",
                progress="resolving_audio",
                error=None,
            )

            source_url = resolve_audio_url(job.get("source_url", ""))
            update_job(job_id, progress="downloading_audio")
            download_track(source_url, source_path)

            update_job(job_id, progress="checking_audio")
            duration = get_audio_duration(source_path)

            update_job(job_id, progress="separating_vocals")
            result = subprocess.run(
                [
                    "python", "-m", "demucs",
                    "--two-stems=vocals",
                    "-n", DEMUCS_MODEL,
                    "-d", "cpu",
                    "--segment", DEMUCS_SEGMENT_SECONDS,
                    "-o", str(output_root),
                    str(source_path),
                ],
                capture_output=True,
                text=True,
                timeout=7200,
                check=True,
            )

            separated_dir = output_root / DEMUCS_MODEL / source_path.stem
            vocals_path = separated_dir / "vocals.wav"
            no_vocals_path = separated_dir / "no_vocals.wav"

            if not vocals_path.is_file() or not no_vocals_path.is_file():
                raise RuntimeError(
                    "Vocal separation did not produce both audio stems. "
                    + (result.stderr or "")[-1000:]
                )

            shutil.move(str(no_vocals_path), str(instrumental_path))
            update_job(job_id, progress="transcribing_lyrics")

            model = get_whisper_model()
            segments, info = model.transcribe(
                str(vocals_path),
                word_timestamps=True,
                vad_filter=True,
            )

            words = []
            for segment in segments:
                for word in getattr(segment, "words", None) or []:
                    words.append({
                        "start": round(float(word.start), 3),
                        "end": round(float(word.end), 3),
                        "text": word.word.strip(),
                    })

            with open(lyrics_path, "w", encoding="utf-8") as output:
                json.dump(
                    {
                        "track_id": job_id,
                        "duration_seconds": duration,
                        "language": getattr(info, "language", None),
                        "words": words,
                    },
                    output,
                    ensure_ascii=False,
                    indent=2,
                )

            source_path.unlink(missing_ok=True)
            shutil.rmtree(output_root, ignore_errors=True)

            update_job(
                job_id,
                status="completed",
                progress="completed",
                track_id=job_id,
                error=None,
            )

        except Exception as exc:
            update_job(
                job_id,
                status="failed",
                progress="failed",
                error=str(exc)[:2000] or "Processing failed. Try another direct audio file.",
            )


# ============================================================
# API ENDPOINTS
# ============================================================

@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "Donut4U Karaoke Processing API",
    }


@app.get("/v1/web-search")
def public_web_search(q: str = Query(min_length=1, max_length=160)):
    cleanup_expired_jobs()
    return {"results": web_search(q.strip())}


@app.get("/v1/catalog-search")
def public_catalog_search(q: str = Query(min_length=1, max_length=160)):
    cleanup_expired_jobs()
    results = web_search(q.strip())
    return {
        "results": [
            {
                "name": item["title"],
                "title": item["title"],
                "artist": item.get("artist", ""),
                "id": item["url"],
                "url": item["url"],
                "source": item.get("source", ""),
                "duration": 0,
            }
            for item in results
        ]
    }


@app.post("/v1/jobs/from-url")
def create_job_from_url(payload: dict, background_tasks: BackgroundTasks):
    try:
        source_url = validate_audio_url(payload.get("url", ""))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    title = str(payload.get("title") or "Audio track")[:250]
    artist = str(payload.get("artist") or "Unknown")[:160]

    cleanup_expired_jobs()
    job_id = secrets.token_urlsafe(18)
    directory = JOB_ROOT / job_id
    directory.mkdir(parents=True, exist_ok=False)
    now = time.time()

    with JOBS_LOCK:
        JOBS[job_id] = {
            "job_id": job_id,
            "track_id": job_id,
            "source_url": source_url,
            "track_name": title,
            "artist": artist,
            "status": "queued",
            "progress": "queued",
            "created_at": utc_now(),
            "updated_at": utc_now(),
            "updated_at_epoch": now,
            "directory": str(directory),
            "error": None,
        }
        save_jobs_locked()

    background_tasks.add_task(process_job, job_id)
    return {"job_id": job_id, "track_id": job_id, "status": "queued"}


@app.get("/v1/jobs/{job_id}")
def job_status(job_id: str):
    job = get_job_or_404(job_id)
    return {
        "job_id": job["job_id"],
        "track_id": job.get("track_id"),
        "track_name": job["track_name"],
        "artist": job["artist"],
        "status": job["status"],
        "progress": job["progress"],
        "error": job.get("error"),
    }


@app.get("/v1/jobs/{job_id}/instrumental")
def get_instrumental(job_id: str):
    job = get_job_or_404(job_id)
    if job["status"] != "completed":
        raise HTTPException(status_code=409, detail="Processing is not complete.")

    path = Path(job["directory"]) / "instrumental.wav"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Instrumental file not found.")

    return FileResponse(
        path,
        media_type="audio/wav",
        filename="donut4u_instrumental.wav",
    )


@app.get("/v1/jobs/{job_id}/lyrics")
def get_lyrics(job_id: str):
    job = get_job_or_404(job_id)
    if job["status"] != "completed":
        raise HTTPException(status_code=409, detail="Processing is not complete.")

    path = Path(job["directory"]) / "lyrics.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Lyrics file not found.")

    with open(path, "r", encoding="utf-8") as source:
        return json.load(source)


@app.delete("/v1/jobs/{job_id}")
def delete_job(job_id: str):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="Job not found.")

        if job["status"] in ("queued", "processing"):
            raise HTTPException(
                status_code=409,
                detail="Cannot delete while queued or processing.",
            )

        directory = job["directory"]
        del JOBS[job_id]
        save_jobs_locked()

    shutil.rmtree(directory, ignore_errors=True)
    return {"deleted": True, "job_id": job_id}


load_jobs()
