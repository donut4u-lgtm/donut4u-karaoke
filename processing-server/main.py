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

from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import urlparse, parse_qs, unquote

import httpx

from fastapi import FastAPI, BackgroundTasks, HTTPException, Query
from fastapi.responses import FileResponse
from faster_whisper import WhisperModel


app = FastAPI(
    title="Donut4U Karaoke Processing API",
    version="2.0.0"
)

JOB_TTL_MINUTES = int(os.getenv("JOB_TTL_MINUTES", "60"))
MAX_TRACK_SECONDS = int(os.getenv("MAX_TRACK_SECONDS", "600"))

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
DEMUCS_MODEL = os.getenv("DEMUCS_MODEL", "htdemucs")

JOB_ROOT = Path(
    os.getenv("JOB_ROOT", "/tmp/donut4u-jobs")
)

MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024

JOBS = {}
JOBS_LOCK = threading.Lock()
WHISPER_LOCK = threading.Lock()
WHISPER_INSTANCE = None


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def cleanup_expired_jobs():
    cutoff = time.time() - JOB_TTL_MINUTES * 60
    expired = []

    with JOBS_LOCK:
        for job_id, job in list(JOBS.items()):
            if job["status"] == "processing":
                continue

            if job.get("updated_at_epoch", time.time()) < cutoff:
                expired.append(job["directory"])
                del JOBS[job_id]

    for directory in expired:
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
        if job_id in JOBS:
            JOBS[job_id].update(changes)
            JOBS[job_id]["updated_at"] = utc_now()
            JOBS[job_id]["updated_at_epoch"] = time.time()


def validate_public_url(url):
    url = (url or "").strip()
    parsed = urlparse(url)

    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError(
            "Use a complete public http:// or https:// URL."
        )

    host = parsed.hostname.lower()

    if host in (
        "localhost",
        "localhost.localdomain"
    ) or host.endswith(".local"):
        raise ValueError(
            "Local network URLs are not supported."
        )

    try:
        for item in socket.getaddrinfo(host, None):
            address = ipaddress.ip_address(item[4][0])

            if not address.is_global:
                raise ValueError(
                    "Private or local network URLs are not supported."
                )

    except socket.gaierror:
        raise ValueError(
            "The URL host could not be resolved."
        )

    return url


def web_search(query):
    try:
        response = httpx.get(
            "https://html.duckduckgo.com/html/",
            params={"q": query.strip()},
            headers={
                "User-Agent":
                    "Mozilla/5.0 (compatible; Donut4UKaraoke/2.0)"
            },
            timeout=20,
            follow_redirects=True,
        )

        response.raise_for_status()

    except httpx.HTTPError:
        raise HTTPException(
            status_code=502,
            detail="Internet search timed out. Try again."
        )

    page = response.text
    results = []

    anchors = re.findall(
        r'<a\b(?=[^>]*class=["\'][^"\']*result__a[^"\']*["\'])[^>]*>.*?</a>',
        page,
        re.I | re.S
    )

    for anchor in anchors[:25]:
        match = re.search(
            r'href=["\']([^"\']+)["\']',
            anchor,
            re.I
        )

        if not match:
            continue

        target = html.unescape(match.group(1))
        parsed = urlparse(target)

        if "duckduckgo.com" in (parsed.hostname or ""):
            redirect = parse_qs(parsed.query).get("uddg", [])

            if redirect:
                target = unquote(redirect[0])

        try:
            target = validate_public_url(target)

        except ValueError:
            continue

        title = html.unescape(
            re.sub(
                r"\s+",
                " ",
                re.sub(r"<[^>]+>", "", anchor)
            )
        ).strip()

        if not title:
            continue

        if any(x["url"] == target for x in results):
            continue

        results.append({
            "title": title[:250],
            "url": target,
            "source": urlparse(target).hostname or ""
        })

    return results


def resolve_audio_url(source_url):
    source_url = validate_public_url(source_url)
    parsed = urlparse(source_url)

    if re.search(
        r"\.(mp3|wav|m4a|aac|ogg|opus|flac|mp4|webm)$",
        parsed.path,
        re.I
    ):
        return source_url

    try:
        response = httpx.get(
            source_url,
            headers={
                "User-Agent":
                    "Mozilla/5.0 (compatible; Donut4UKaraoke/2.0)"
            },
            timeout=25,
            follow_redirects=True
        )

        response.raise_for_status()

        content_type = response.headers.get(
            "content-type",
            ""
        ).lower()

        if content_type.startswith("audio/"):
            return str(response.url)

        if (
            "text/html" not in content_type
            and "application/xhtml" not in content_type
        ):
            raise ValueError(
                "This link is not an accessible audio file or web page."
            )

        page = response.text[:5_000_000]

    except httpx.HTTPError:
        raise ValueError(
            "Could not open the page. Try a direct audio-file link."
        )

    patterns = [
        r'<meta[^>]+(?:property|name)=["\'](?:og:audio|og:audio:url|twitter:player:stream)["\'][^>]+content=["\']([^"\']+)["\']',
        r'<audio[^>]+src=["\']([^"\']+)["\']',
        r'<source[^>]+src=["\']([^"\']+)["\']',
        r'["\'](?:contentUrl|audioUrl|audio_url|downloadUrl)["\']\s*:\s*["\']([^"\']+)["\']',
        r'["\'](https?://[^"\']+\.(?:mp3|wav|m4a|aac|ogg|opus|flac)(?:\?[^"\']*)?)["\']',
    ]

    for pattern in patterns:
        match = re.search(pattern, page, re.I)

        if not match:
            continue

        candidate = html.unescape(
            match.group(1)
        ).replace("\\/", "/")

        if candidate.startswith("//"):
            candidate = parsed.scheme + ":" + candidate

        elif candidate.startswith("/"):
            candidate = (
                f"{parsed.scheme}://{parsed.netloc}{candidate}"
            )

        try:
            return validate_public_url(candidate)

        except ValueError:
            continue

    raise ValueError(
        "No accessible audio file was found on this page. "
        "Try another result or a direct audio link."
    )


def download_track(url, destination):
    total = 0

    with httpx.stream(
        "GET",
        url,
        follow_redirects=True,
        timeout=120,
        headers={"User-Agent": "Mozilla/5.0"}
    ) as response:

        response.raise_for_status()

        content_type = response.headers.get(
            "content-type",
            ""
        ).lower()

        path = urlparse(
            str(response.url)
        ).path.lower()

        if (
            not content_type.startswith("audio/")
            and not re.search(
                r"\.(mp3|wav|m4a|aac|ogg|opus|flac|mp4|webm)$",
                path
            )
        ):
            raise ValueError(
                "Selected URL did not return an audio file."
            )

        size = response.headers.get("content-length")

        if size and int(size) > MAX_DOWNLOAD_BYTES:
            raise ValueError(
                "Audio file exceeds the 100 MB limit."
            )

        with open(destination, "wb") as output:
            for chunk in response.iter_bytes(1024 * 1024):
                total += len(chunk)

                if total > MAX_DOWNLOAD_BYTES:
                    raise ValueError(
                        "Audio file exceeds the 100 MB limit."
                    )

                output.write(chunk)

    if total == 0:
        raise ValueError(
            "The downloaded audio file is empty."
        )


def get_audio_duration(path):
    result = subprocess.run(
        [
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path)
        ],
        capture_output=True,
        text=True,
        timeout=30,
        check=True
    )

    duration = float(result.stdout.strip())

    if duration <= 0 or duration > MAX_TRACK_SECONDS:
        raise ValueError(
            f"Audio must be between 1 second and "
            f"{MAX_TRACK_SECONDS} seconds."
        )

    return duration


def get_whisper_model():
    global WHISPER_INSTANCE

    with WHISPER_LOCK:
        if WHISPER_INSTANCE is None:
            WHISPER_INSTANCE = WhisperModel(
                WHISPER_MODEL,
                device="cpu",
                compute_type="int8"
            )

        return WHISPER_INSTANCE


def process_job(job_id):
    job = get_job_or_404(job_id)

    directory = Path(job["directory"])
    source_path = directory / "source-audio"
    output_root = directory / "separated"
    instrumental_path = directory / "instrumental.wav"
    lyrics_path = directory / "lyrics.json"

    try:
        update_job(
            job_id,
            status="processing",
            progress="resolving_audio"
        )

        source_url = resolve_audio_url(
            job.get("source_url", "")
        )

        download_track(source_url, source_path)

        update_job(
            job_id,
            progress="checking_audio"
        )

        duration = get_audio_duration(source_path)

        update_job(
            job_id,
            progress="separating_vocals"
        )

        subprocess.run(
            [
                "python",
                "-m", "demucs",
                "--two-stems=vocals",
                "-n", DEMUCS_MODEL,
                "-d", "cpu",
                "-o", str(output_root),
                str(source_path)
            ],
            capture_output=True,
            text=True,
            timeout=7200,
            check=True
        )

        separated_dir = (
            output_root
            / DEMUCS_MODEL
            / source_path.stem
        )

        vocals_path = separated_dir / "vocals.wav"
        no_vocals_path = separated_dir / "no_vocals.wav"

        if (
            not vocals_path.is_file()
            or not no_vocals_path.is_file()
        ):
            raise RuntimeError(
                "Vocal separation did not produce both audio stems."
            )

        shutil.move(
            str(no_vocals_path),
            str(instrumental_path)
        )

        update_job(
            job_id,
            progress="transcribing_lyrics"
        )

        model = get_whisper_model()

        segments, info = model.transcribe(
            str(vocals_path),
            word_timestamps=True,
            vad_filter=True
        )

        words = []

        for segment in segments:
            for word in (
                getattr(segment, "words", None) or []
            ):
                words.append({
                    "start": round(float(word.start), 3),
                    "end": round(float(word.end), 3),
                    "text": word.word.strip()
                })

        with open(
            lyrics_path,
            "w",
            encoding="utf-8"
        ) as out:
            json.dump(
                {
                    "track_id": job_id,
                    "duration_seconds": duration,
                    "language": getattr(info, "language", None),
                    "words": words
                },
                out,
                ensure_ascii=False,
                indent=2
            )

        source_path.unlink(missing_ok=True)

        shutil.rmtree(
            output_root,
            ignore_errors=True
        )

        update_job(
            job_id,
            status="completed",
            progress="completed"
        )

    except Exception as exc:
        shutil.rmtree(
            directory,
            ignore_errors=True
        )

        update_job(
            job_id,
            status="failed",
            progress="failed",
            error=str(exc)[:400]
            or "Processing failed. Try another audio link."
        )


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "Donut4U Karaoke Processing API"
    }


@app.get("/v1/web-search")
def public_web_search(
    q: str = Query(min_length=1, max_length=160)
):
    cleanup_expired_jobs()

    return {
        "results": web_search(q.strip())
    }


@app.post("/v1/jobs/from-url")
def create_job_from_url(
    payload: dict,
    background_tasks: BackgroundTasks
):
    try:
        source_url = validate_public_url(
            payload.get("url", "")
        )

    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail=str(exc)
        )

    title = str(
        payload.get("title") or "Internet audio"
    )[:250]

    artist = str(
        payload.get("artist") or "Unknown"
    )[:160]

    cleanup_expired_jobs()

    job_id = secrets.token_urlsafe(18)
    directory = JOB_ROOT / job_id
    directory.mkdir(parents=True, exist_ok=False)

    now = time.time()

    with JOBS_LOCK:
        JOBS[job_id] = {
            "job_id": job_id,
            "track_id": None,
            "source_url": source_url,
            "track_name": title,
            "artist": artist,
            "status": "queued",
            "progress": "queued",
            "created_at": utc_now(),
            "updated_at": utc_now(),
            "updated_at_epoch": now,
            "directory": str(directory),
            "error": None
        }

    background_tasks.add_task(
        process_job,
        job_id
    )

    return {
        "job_id": job_id,
        "status": "queued"
    }


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
        "error": job["error"]
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
        filename="donut4u_instrumental.wav"
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

    with open(
        path,
        "r",
        encoding="utf-8"
    ) as source:
        return json.load(source)


@app.delete("/v1/jobs/{job_id}")
def delete_job(job_id: str):
    with JOBS_LOCK:
        job = JOBS.get(job_id)

        if not job:
            raise HTTPException(
                status_code=404,
                detail="Job not found."
            )

        if job["status"] == "processing":
            raise HTTPException(
                status_code=409,
                detail="Cannot delete while processing."
            )

        directory = job["directory"]
        del JOBS[job_id]

    shutil.rmtree(
        directory,
        ignore_errors=True
    )

    return {
        "deleted": True,
        "job_id": job_id
    }
