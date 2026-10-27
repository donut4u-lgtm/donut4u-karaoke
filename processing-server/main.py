
import os
import re
import json
import time
import shutil
import secrets
import threading
import subprocess
import ipaddress
import socket
import tempfile
import html

from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urlparse, urlencode, parse_qs, unquote
from html.parser import HTMLParser

import httpx
import yt_dlp

from fastapi import (
    FastAPI, BackgroundTasks, HTTPException, Query,
    UploadFile, File, Form,
)
from fastapi.responses import FileResponse
from faster_whisper import WhisperModel


app = FastAPI(
    title="Donut4U Karaoke Processing API",
    version="2.5.0",
)

JOB_TTL_MINUTES = int(os.getenv("JOB_TTL_MINUTES", "60"))
MAX_TRACK_SECONDS = int(os.getenv("MAX_TRACK_SECONDS", "600"))
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "tiny")
DEMUCS_MODEL = os.getenv("DEMUCS_MODEL", "htdemucs")
DEMUCS_SEGMENT_SECONDS = os.getenv("DEMUCS_SEGMENT_SECONDS", "7.8")

JOB_ROOT = Path(os.getenv("JOB_ROOT", "/tmp/donut4u-jobs"))
JOBS_FILE = JOB_ROOT / "jobs.json"

MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
MAX_UPLOAD_BYTES = 100 * 1024 * 1024

SUPPORTED_AUDIO_EXTENSIONS = {
    ".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus",
    ".flac", ".webm", ".mp4", ".mpeg", ".mpga", ".wma",
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


# ============================================================
# GENERAL WEB SEARCH HTML PARSER
# ============================================================

class DuckDuckGoResultParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.results = []
        self.current_result = None
        self.capture_title = False
        self.capture_snippet = False
        self.title_parts = []
        self.snippet_parts = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        classes = set(attributes.get("class", "").split())

        if tag == "a" and "result__a" in classes:
            self.current_result = {
                "href": attributes.get("href", ""),
                "title": "",
                "snippet": "",
            }
            self.title_parts = []
            self.capture_title = True

        elif (
            tag in ("a", "div", "span")
            and "result__snippet" in classes
        ):
            self.snippet_parts = []
            self.capture_snippet = True

    def handle_data(self, data):
        if self.capture_title:
            self.title_parts.append(data)
        if self.capture_snippet:
            self.snippet_parts.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.capture_title:
            if self.current_result is not None:
                self.current_result["title"] = " ".join(
                    "".join(self.title_parts).split()
                )
                self.results.append(self.current_result)

            self.current_result = None
            self.capture_title = False
            self.title_parts = []

        if tag in ("a", "div", "span") and self.capture_snippet:
            snippet = " ".join("".join(self.snippet_parts).split())

            if snippet and self.results:
                if not self.results[-1].get("snippet"):
                    self.results[-1]["snippet"] = snippet

            self.capture_snippet = False
            self.snippet_parts = []


def normalize_search_result_url(raw_url):
    raw_url = html.unescape((raw_url or "").strip())

    if raw_url.startswith("//"):
        raw_url = "https:" + raw_url

    parsed = urlparse(raw_url)

    if (
        parsed.hostname
        and parsed.hostname.lower().endswith("duckduckgo.com")
    ):
        values = parse_qs(parsed.query)
        target = values.get("uddg", [""])[0]
        if target:
            raw_url = unquote(target)

    parsed = urlparse(raw_url)

    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return None

    hostname = parsed.hostname.lower()

    if hostname == "duckduckgo.com" or hostname.endswith(".duckduckgo.com"):
        return None

    return raw_url


# ============================================================
# JOB STORAGE
# ============================================================

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
                detail="Job not found or expired."
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


def create_job_record(*, title, artist, source_url=None, source_file=None):
    cleanup_expired_jobs()

    job_id = secrets.token_urlsafe(18)
    directory = JOB_ROOT / job_id
    directory.mkdir(parents=True, exist_ok=False)

    now = time.time()

    job = {
        "job_id": job_id,
        "track_id": job_id,
        "source_url": source_url,
        "source_file": source_file,
        "track_name": str(title or "Audio track")[:250],
        "artist": str(artist or "Unknown")[:160],
        "status": "queued",
        "progress": "queued",
        "created_at": utc_now(),
        "updated_at": utc_now(),
        "updated_at_epoch": now,
        "directory": str(directory),
        "error": None,
    }

    with JOBS_LOCK:
        JOBS[job_id] = job
        save_jobs_locked()

    return job


# ============================================================
# PUBLIC URL VALIDATION
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
            and parsed.path.lower().endswith(tuple(SUPPORTED_AUDIO_EXTENSIONS))
        )
    except (TypeError, ValueError):
        return False


def validate_audio_url(url):
    return validate_public_url(url)


# ============================================================
# WEB SEARCH WITH GOOGLE FALLBACK
# ============================================================

def google_search_fallback(query):
    query = (query or "").strip()
    if not query:
        return []

    search_query = query + " music song karaoke"
    search_url = (
        "https://www.google.com/search?"
        + urlencode({"q": search_query})
    )
    title = "Search results for: " + query[:180]

    return [{
        "title": title,
        "name": title,
        "url": search_url,
        "page_url": search_url,
        "id": search_url,
        "artist": "",
        "snippet": "Open these music search results in Donut4U Karaoke.",
        "source": "Google Search",
        "duration": 0,
        "preview": False,
        "open_in_webview": True,
    }]


def web_search(query):
    query = (query or "").strip()
    if not query:
        return []

    search_query = query + " music song karaoke"
    search_url = (
        "https://html.duckduckgo.com/html/?"
        + urlencode({"q": search_query})
    )

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Linux; Android 15) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Mobile Safari/537.36"
        ),
        "Accept": (
            "text/html,application/xhtml+xml,"
            "application/xml;q=0.9,*/*;q=0.8"
        ),
        "Accept-Language": "en-US,en;q=0.9",
    }

    try:
        response = httpx.get(
            search_url,
            headers=headers,
            timeout=25.0,
            follow_redirects=True,
        )
        response.raise_for_status()
        page = response.text
    except httpx.HTTPError:
        return google_search_fallback(query)

    parser = DuckDuckGoResultParser()

    try:
        parser.feed(page)
    except Exception:
        return google_search_fallback(query)

    results = []
    seen_urls = set()

    for item in parser.results:
        target_url = normalize_search_result_url(item.get("href", ""))

        if not target_url or target_url in seen_urls:
            continue

        seen_urls.add(target_url)

        title = (
            " ".join(
                html.unescape(item.get("title", "")).split()
            )[:250]
            or target_url
        )
        snippet = " ".join(
            html.unescape(item.get("snippet", "")).split()
        )[:600]

        hostname = urlparse(target_url).hostname or ""

        results.append({
            "title": title,
            "name": title,
            "url": target_url,
            "page_url": target_url,
            "id": target_url,
            "artist": "",
            "snippet": snippet,
            "source": hostname,
            "duration": 0,
            "preview": False,
            "open_in_webview": True,
        })

    if not results:
        return google_search_fallback(query)

    return results


def resolve_audio_url(source_url):
    return validate_public_url(source_url)


# ============================================================
# AUDIO DOWNLOAD
# ============================================================

def download_track(url, destination):
    current_url = validate_public_url(url)
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)

    if is_direct_audio_url(current_url):
        total = 0

        with httpx.stream(
            "GET",
            current_url,
            follow_redirects=False,
            timeout=httpx.Timeout(120.0, connect=10.0),
            headers={"User-Agent": "Donut4U-Karaoke/2.5.0"},
        ) as response:
            if response.is_redirect:
                raise ValueError(
                    "This audio link redirects. Use its final public audio URL instead."
                )

            response.raise_for_status()
            content_length = response.headers.get("content-length")

            if content_length:
                try:
                    if int(content_length) > MAX_DOWNLOAD_BYTES:
                        raise ValueError("Audio file exceeds the 100 MB limit.")
                except ValueError as exc:
                    if "100 MB" in str(exc):
                        raise

            with open(destination, "wb") as output:
                for chunk in response.iter_bytes(1024 * 1024):
                    if not chunk:
                        continue

                    total += len(chunk)
                    if total > MAX_DOWNLOAD_BYTES:
                        raise ValueError("Audio file exceeds the 100 MB limit.")

                    output.write(chunk)

        if total == 0:
            raise ValueError("The downloaded audio file is empty.")

        return destination

    temporary_dir = destination.parent / "media-download"
    temporary_dir.mkdir(parents=True, exist_ok=True)

    options = {
        "format": "bestaudio/best",
        "outtmpl": str(temporary_dir / "source.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "socket_timeout": 30,
        "max_filesize": MAX_DOWNLOAD_BYTES,
        "overwrites": True,
    }

    try:
        with yt_dlp.YoutubeDL(options) as downloader:
            info = downloader.extract_info(current_url, download=True)

        if not info:
            raise ValueError("The media source returned no track information.")

        if info.get("_type") == "playlist":
            raise ValueError("Playlists are not supported. Select one track.")

        duration = info.get("duration")
        if duration and duration > MAX_TRACK_SECONDS:
            raise ValueError(
                f"Audio must be no longer than {MAX_TRACK_SECONDS} seconds."
            )

        candidates = [
            path for path in temporary_dir.glob("source.*")
            if path.is_file()
        ]

        if not candidates:
            raise ValueError("The media source did not produce an audio file.")

        downloaded = max(candidates, key=lambda path: path.stat().st_size)

        if downloaded.stat().st_size > MAX_DOWNLOAD_BYTES:
            raise ValueError("Audio file exceeds the 100 MB limit.")

        shutil.move(str(downloaded), str(destination))
        return destination

    finally:
        shutil.rmtree(temporary_dir, ignore_errors=True)


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
                WHISPER_MODEL,
                device="cpu",
                compute_type="int8",
            )

        return WHISPER_INSTANCE


# ============================================================
# KARAOKE PROCESSING
# ============================================================

def process_job(job_id):
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

            uploaded_file = job.get("source_file")

            if uploaded_file:
                source_path = Path(uploaded_file)
                if not source_path.is_file():
                    raise ValueError("The uploaded audio file is missing.")
            else:
                source_url = resolve_audio_url(job.get("source_url", ""))
                update_job(job_id, progress="downloading_audio")
                source_path = download_track(source_url, source_path)

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
                for word in (getattr(segment, "words", None) or []):
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
                source_file=None,
                error=None,
            )

        except Exception as exc:
            update_job(
                job_id,
                status="failed",
                progress="failed",
                error=(
                    str(exc)[:2000]
                    or "Processing failed. Try another supported audio source."
                ),
            )


# ============================================================
# KARAOKE VIDEO HELPERS
# ============================================================

def srt_timestamp(seconds):
    milliseconds = max(0, int(round(float(seconds) * 1000)))
    hours = milliseconds // 3600000
    minutes = (milliseconds // 60000) % 60
    secs = (milliseconds // 1000) % 60
    millis = milliseconds % 1000

    return (
        f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"
    )


def create_karaoke_srt(lyrics_path, srt_path):
    try:
        with open(lyrics_path, "r", encoding="utf-8") as source:
            data = json.load(source)
    except (OSError, ValueError) as exc:
        raise ValueError("Could not read the detected lyrics.") from exc

    words = data.get("words", [])

    if not isinstance(words, list) or not words:
        raise ValueError(
            "No lyrics were detected, so a lyric video cannot be created."
        )

    groups = []
    current = []

    for word in words:
        if not isinstance(word, dict):
            continue

        text = str(word.get("text", "")).strip()
        if not text:
            continue

        try:
            start = max(0.0, float(word.get("start", 0)))
            end = max(start + 0.15, float(word.get("end", start + 0.3)))
        except (TypeError, ValueError):
            continue

        current.append((start, end, text))

        if len(current) >= 7 or re.search(r"[.!?,;:]$", text):
            groups.append(current)
            current = []

    if current:
        groups.append(current)

    if not groups:
        raise ValueError("No usable lyric timestamps were detected.")

    with open(srt_path, "w", encoding="utf-8") as output:
        for index, group in enumerate(groups, start=1):
            start = group[0][0]
            end = max(group[-1][1], start + 0.5)
            text = " ".join(item[2] for item in group)
            text = text.replace("-->", "—>")

            output.write(
                f"{index}\n"
                f"{srt_timestamp(start)} --> {srt_timestamp(end)}\n"
                f"{text}\n\n"
            )


def escape_subtitle_filter_path(path):
    value = str(path.resolve())
    value = value.replace("\\", "\\\\\\\\")
    value = value.replace(":", "\\\\:")
    value = value.replace("'", "\\\\'")
    value = value.replace(",", "\\\\,")
    value = value.replace("[", "\\\\[")
    value = value.replace("]", "\\\\]")
    return value


# ============================================================
# API ENDPOINTS
# ============================================================

@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "Donut4U Karaoke Processing API",
        "version": app.version,
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
                "page_url": item["url"],
                "source": item.get("source", ""),
                "snippet": item.get("snippet", ""),
                "duration": item.get("duration", 0),
                "preview": False,
                "open_in_webview": True,
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

    job = create_job_record(
        title=payload.get("title") or "Audio track",
        artist=payload.get("artist") or "Unknown",
        source_url=source_url,
    )

    background_tasks.add_task(process_job, job["job_id"])

    return {
        "job_id": job["job_id"],
        "track_id": job["track_id"],
        "status": "queued",
    }


@app.post("/v1/jobs/from-upload")
def create_job_from_upload(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    title: str = Form("Audio upload"),
    artist: str = Form("Unknown"),
):
    original_name = Path(file.filename or "audio").name
    extension = Path(original_name).suffix.lower()

    if extension not in SUPPORTED_AUDIO_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=(
                "Unsupported file extension. Upload MP3, WAV, M4A, AAC, "
                "OGG, OPUS, FLAC, WEBM, MP4, MPGA, or WMA audio."
            ),
        )

    job = create_job_record(
        title=title or Path(original_name).stem,
        artist=artist,
    )

    directory = Path(job["directory"])
    upload_path = directory / ("uploaded-audio" + extension)
    total = 0

    try:
        with open(upload_path, "wb") as output:
            while True:
                chunk = file.file.read(1024 * 1024)
                if not chunk:
                    break

                total += len(chunk)
                if total > MAX_UPLOAD_BYTES:
                    raise HTTPException(
                        status_code=413,
                        detail="Upload exceeds the 100 MB limit.",
                    )

                output.write(chunk)

        if total == 0:
            raise HTTPException(
                status_code=400,
                detail="The uploaded file is empty."
            )

        update_job(
            job["job_id"],
            source_file=str(upload_path),
            progress="queued",
        )

        background_tasks.add_task(process_job, job["job_id"])

        return {
            "job_id": job["job_id"],
            "track_id": job["track_id"],
            "status": "queued",
            "filename": original_name,
        }

    except Exception:
        with JOBS_LOCK:
            JOBS.pop(job["job_id"], None)
            save_jobs_locked()

        shutil.rmtree(directory, ignore_errors=True)
        raise

    finally:
        try:
            file.file.close()
        except Exception:
            pass


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
        raise HTTPException(
            status_code=409,
            detail="Processing is not complete."
        )

    path = Path(job["directory"]) / "instrumental.wav"

    if not path.is_file():
        raise HTTPException(
            status_code=404,
            detail="Instrumental file not found."
        )

    return FileResponse(
        path,
        media_type="audio/wav",
        filename="donut4u_instrumental.wav",
    )


@app.get("/v1/jobs/{job_id}/instrumental-mp3")
def get_instrumental_mp3(job_id: str):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(
            status_code=409,
            detail="Processing is not complete."
        )

    directory = Path(job["directory"])
    wav_file = directory / "instrumental.wav"
    mp3_file = directory / "instrumental.mp3"

    if not wav_file.is_file():
        raise HTTPException(
            status_code=404,
            detail="Instrumental WAV file not found."
        )

    if not mp3_file.is_file():
        try:
            subprocess.run(
                [
                    "ffmpeg", "-y", "-v", "error",
                    "-i", str(wav_file),
                    "-vn", "-codec:a", "libmp3lame",
                    "-b:a", "192k",
                    str(mp3_file),
                ],
                capture_output=True,
                text=True,
                timeout=600,
                check=True,
            )
        except subprocess.TimeoutExpired as exc:
            raise HTTPException(
                status_code=504,
                detail="MP3 conversion timed out."
            ) from exc
        except subprocess.CalledProcessError as exc:
            raise HTTPException(
                status_code=500,
                detail=(exc.stderr or "MP3 conversion failed.")[-1200:]
            ) from exc

    return FileResponse(
        mp3_file,
        media_type="audio/mpeg",
        filename="donut4u_instrumental.mp3",
    )


@app.get("/v1/jobs/{job_id}/lyrics")
def get_lyrics(job_id: str):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(
            status_code=409,
            detail="Processing is not complete."
        )

    path = Path(job["directory"]) / "lyrics.json"

    if not path.is_file():
        raise HTTPException(
            status_code=404,
            detail="Lyrics file not found."
        )

    with open(path, "r", encoding="utf-8") as source:
        return json.load(source)


@app.post("/v1/jobs/{job_id}/video")
def render_karaoke_video(job_id: str):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(
            status_code=409,
            detail="Processing is not complete."
        )

    directory = Path(job["directory"])
    instrumental = directory / "instrumental.wav"
    lyrics = directory / "lyrics.json"
    subtitles = directory / "karaoke-lyrics.srt"
    video = directory / "karaoke-video.mp4"

    if video.is_file() and video.stat().st_size > 0:
        return {
            "job_id": job_id,
            "status": "completed",
            "video_url": f"/v1/jobs/{job_id}/video",
        }

    if not instrumental.is_file() or not lyrics.is_file():
        raise HTTPException(
            status_code=404,
            detail="Instrumental audio or lyrics file is missing."
        )

    try:
        duration = get_audio_duration(instrumental)
        create_karaoke_srt(lyrics, subtitles)

        subtitle_path = escape_subtitle_filter_path(subtitles)
        video_filter = (
            f"subtitles='{subtitle_path}':"
            "force_style='FontName=DejaVu Sans,FontSize=30,"
            "PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
            "BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=55'"
        )

        subprocess.run(
            [
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi",
                "-i",
                f"color=c=black:s=1280x720:r=30:d={duration}",
                "-i", str(instrumental),
                "-vf", video_filter,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-t", str(duration),
                "-movflags", "+faststart",
                str(video),
            ],
            capture_output=True,
            text=True,
            timeout=1200,
            check=True,
        )

    except ValueError as exc:
        raise HTTPException(
            status_code=422,
            detail=str(exc)
        ) from exc
    except subprocess.TimeoutExpired as exc:
        video.unlink(missing_ok=True)
        raise HTTPException(
            status_code=504,
            detail="Video rendering timed out."
        ) from exc
    except subprocess.CalledProcessError as exc:
        video.unlink(missing_ok=True)
        raise HTTPException(
            status_code=500,
            detail=(exc.stderr or "Video rendering failed.")[-1500:]
        ) from exc

    if not video.is_file() or video.stat().st_size == 0:
        raise HTTPException(
            status_code=500,
            detail="The MP4 file was not created."
        )

    return {
        "job_id": job_id,
        "status": "completed",
        "video_url": f"/v1/jobs/{job_id}/video",
        "filename": video.name,
    }


@app.get("/v1/jobs/{job_id}/video")
def get_karaoke_video(job_id: str):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(
            status_code=409,
            detail="Processing is not complete."
        )

    video = Path(job["directory"]) / "karaoke-video.mp4"

    if not video.is_file():
        raise HTTPException(
            status_code=404,
            detail="Karaoke video has not been rendered yet."
        )

    return FileResponse(
        video,
        media_type="video/mp4",
        filename="donut4u_karaoke.mp4",
    )


@app.delete("/v1/jobs/{job_id}")
def delete_job(job_id: str):
    with JOBS_LOCK:
        job = JOBS.get(job_id)

        if not job:
            raise HTTPException(
                status_code=404,
                detail="Job not found."
            )

        if job["status"] in ("queued", "processing"):
            raise HTTPException(
                status_code=409,
                detail="Cannot delete while queued or processing."
            )

        directory = job["directory"]
        del JOBS[job_id]
        save_jobs_locked()

    shutil.rmtree(directory, ignore_errors=True)

    return {"deleted": True, "job_id": job_id}


load_jobs()
