
import os
import re
import json
import time
import shutil
import secrets
import threading
import subprocess
from pathlib import Path
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

import httpx
from fastapi import FastAPI, BackgroundTasks, Depends, Header, HTTPException, Query
from fastapi.responses import FileResponse
from faster_whisper import WhisperModel


app = FastAPI(title="Donut4U Karaoke Processing API", version="1.0.0")

JAMENDO_API = "https://api.jamendo.com/v3.0"
JAMENDO_CLIENT_ID = os.getenv("JAMENDO_CLIENT_ID", "")
API_TOKEN = os.getenv("DONUT4U_API_TOKEN", "")
JOB_TTL_MINUTES = int(os.getenv("JOB_TTL_MINUTES", "60"))
MAX_TRACK_SECONDS = int(os.getenv("MAX_TRACK_SECONDS", "600"))
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
DEMUCS_MODEL = os.getenv("DEMUCS_MODEL", "htdemucs")
JOB_ROOT = Path(os.getenv("JOB_ROOT", "/tmp/donut4u-jobs"))

MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024
JOBS = {}
JOBS_LOCK = threading.Lock()
WHISPER_LOCK = threading.Lock()
WHISPER_INSTANCE = None


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def require_token(x_api_token: str = Header(default="")):
    if not API_TOKEN:
        raise HTTPException(
            status_code=503,
            detail="Server API token is not configured."
        )

    if not secrets.compare_digest(x_api_token, API_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid API token.")

    return True


def cleanup_expired_jobs():
    """Delete completed or failed jobs after their retention period."""
    cutoff = time.time() - (JOB_TTL_MINUTES * 60)
    expired_paths = []

    with JOBS_LOCK:
        for job_id, job in list(JOBS.items()):
            if job["status"] == "processing":
                continue

            if job.get("updated_at_epoch", time.time()) < cutoff:
                expired_paths.append(job["directory"])
                del JOBS[job_id]

    for directory in expired_paths:
        shutil.rmtree(directory, ignore_errors=True)


def get_job_or_404(job_id):
    cleanup_expired_jobs()

    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if job is None:
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


def jamendo_request(params):
    if not JAMENDO_CLIENT_ID:
        raise HTTPException(
            status_code=503,
            detail="Jamendo client ID is not configured."
        )

    request_params = {
        "client_id": JAMENDO_CLIENT_ID,
        "format": "json",
        **params,
    }

    try:
        response = httpx.get(
            f"{JAMENDO_API}/tracks/",
            params=request_params,
            timeout=20,
        )
        response.raise_for_status()
        data = response.json()
    except (httpx.HTTPError, ValueError):
        raise HTTPException(
            status_code=502,
            detail="Could not contact the music catalog."
        )

    if data.get("headers", {}).get("status") != "success":
        raise HTTPException(
            status_code=502,
            detail="The music catalog did not return a successful response."
        )

    return data.get("results", [])


def find_permitted_track(track_id):
    tracks = jamendo_request({
        "id": str(track_id),
        "limit": 1,
        "audioformat": "mp32",
    })

    if not tracks:
        raise ValueError("Track not found in the catalog.")

    track = tracks[0]

    if not track.get("audiodownload_allowed"):
        raise ValueError("The catalog does not permit downloading this track.")

    download_url = track.get("audiodownload")
    if not download_url or not download_url.startswith("https://"):
        raise ValueError("No permitted HTTPS audio download is available.")

    return track


def download_track(url, destination):
    total_bytes = 0

    with httpx.stream(
        "GET",
        url,
        follow_redirects=True,
        timeout=120,
    ) as response:
        response.raise_for_status()

        content_length = response.headers.get("content-length")
        if content_length and int(content_length) > MAX_DOWNLOAD_BYTES:
            raise ValueError("Audio file exceeds the download size limit.")

        with open(destination, "wb") as output:
            for chunk in response.iter_bytes(chunk_size=1024 * 1024):
                total_bytes += len(chunk)

                if total_bytes > MAX_DOWNLOAD_BYTES:
                    raise ValueError("Audio file exceeds the download size limit.")

                output.write(chunk)

    if total_bytes == 0:
        raise ValueError("The downloaded audio file is empty.")


def get_audio_duration(audio_path):
    result = subprocess.run(
        [
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        ],
        capture_output=True,
        text=True,
        timeout=30,
        check=True,
    )

    duration = float(result.stdout.strip())

    if duration <= 0 or duration > MAX_TRACK_SECONDS:
        raise ValueError(
            f"Track must be between 1 second and {MAX_TRACK_SECONDS} seconds."
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


def process_job(job_id, track_id):
    job = get_job_or_404(job_id)
    directory = Path(job["directory"])
    source_path = directory / "source.mp3"
    output_root = directory / "separated"
    instrumental_path = directory / "instrumental.wav"
    lyrics_path = directory / "lyrics.json"

    try:
        update_job(job_id, status="processing", progress="downloading")

        track = find_permitted_track(track_id)
        download_track(track["audiodownload"], source_path)

        update_job(job_id, progress="checking_audio")
        duration = get_audio_duration(source_path)

        update_job(job_id, progress="separating_vocals")

        subprocess.run(
            [
                "python", "-m", "demucs",
                "--two-stems=vocals",
                "-n", DEMUCS_MODEL,
                "-d", "cpu",
                "-o", str(output_root),
                str(source_path),
            ],
            capture_output=True,
            text=True,
            timeout=7200,
            check=True,
        )

        separated_dir = output_root / DEMUCS_MODEL / "source"
        vocals_path = separated_dir / "vocals.wav"
        no_vocals_path = separated_dir / "no_vocals.wav"

        if not vocals_path.is_file() or not no_vocals_path.is_file():
            raise RuntimeError("Vocal separation did not produce both audio stems.")

        shutil.move(str(no_vocals_path), str(instrumental_path))

        update_job(job_id, progress="transcribing_lyrics")

        model = get_whisper_model()
        segments, info = model.transcribe(
            str(vocals_path),
            word_timestamps=True,
            vad_filter=True,
        )

        timed_words = []
        for segment in segments:
            words = getattr(segment, "words", None) or []

            for word in words:
                timed_words.append({
                    "start": round(float(word.start), 3),
                    "end": round(float(word.end), 3),
                    "text": word.word.strip(),
                })

        with open(lyrics_path, "w", encoding="utf-8") as output:
            json.dump(
                {
                    "track_id": str(track_id),
                    "duration_seconds": duration,
                    "language": getattr(info, "language", None),
                    "words": timed_words,
                },
                output,
                ensure_ascii=False,
                indent=2,
            )

        # Keep only the instrumental and timed lyrics.
        source_path.unlink(missing_ok=True)
        shutil.rmtree(output_root, ignore_errors=True)

        update_job(
            job_id,
            status="completed",
            progress="completed",
            track_name=track.get("name", "Unknown"),
            artist=track.get("artist_name", "Unknown"),
        )

    except Exception as exc:
        # Do not return internal errors or credentials to the client.
        shutil.rmtree(directory, ignore_errors=True)

        update_job(
            job_id,
            status="failed",
            progress="failed",
            error="Processing failed. Please try another track.",
        )


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "Donut4U Karaoke Processing API",
    }


@app.get("/v1/search")
def search_tracks(
    q: str = Query(min_length=1, max_length=100),
    _: bool = Depends(require_token),
):
    cleanup_expired_jobs()

    tracks = jamendo_request({
        "search": q,
        "limit": 20,
        "audioformat": "mp32",
    })

    results = []

    for track in tracks:
        if not track.get("audiodownload_allowed"):
            continue

        results.append({
            "id": str(track.get("id", "")),
            "name": track.get("name", ""),
            "artist": track.get("artist_name", ""),
            "duration": track.get("duration"),
            "image": track.get("image"),
        })

    return {"results": results}


@app.post("/v1/jobs/from-track/{track_id}")
def create_job(
    track_id: str,
    background_tasks: BackgroundTasks,
    _: bool = Depends(require_token),
):
    if not track_id.isdigit():
        raise HTTPException(status_code=400, detail="Invalid track ID.")

    cleanup_expired_jobs()

    try:
        track = find_permitted_track(track_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    job_id = secrets.token_urlsafe(18)
    job_directory = JOB_ROOT / job_id
    job_directory.mkdir(parents=True, exist_ok=False)

    now = time.time()

    with JOBS_LOCK:
        JOBS[job_id] = {
            "job_id": job_id,
            "track_id": str(track_id),
            "track_name": track.get("name", "Unknown"),
            "artist": track.get("artist_name", "Unknown"),
            "status": "queued",
            "progress": "queued",
            "created_at": utc_now(),
            "updated_at": utc_now(),
            "updated_at_epoch": now,
            "directory": str(job_directory),
            "error": None,
        }

    background_tasks.add_task(process_job, job_id, track_id)

    return {
        "job_id": job_id,
        "status": "queued",
    }


@app.get("/v1/jobs/{job_id}")
def job_status(
    job_id: str,
    _: bool = Depends(require_token),
):
    job = get_job_or_404(job_id)

    return {
        "job_id": job["job_id"],
        "track_id": job["track_id"],
        "track_name": job["track_name"],
        "artist": job["artist"],
        "status": job["status"],
        "progress": job["progress"],
        "error": job["error"],
    }


@app.get("/v1/jobs/{job_id}/instrumental")
def get_instrumental(
    job_id: str,
    _: bool = Depends(require_token),
):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(status_code=409, detail="Processing is not complete.")

    audio_path = Path(job["directory"]) / "instrumental.wav"

    if not audio_path.is_file():
        raise HTTPException(status_code=404, detail="Instrumental file not found.")

    return FileResponse(
        audio_path,
        media_type="audio/wav",
        filename="donut4u_instrumental.wav",
    )


@app.get("/v1/jobs/{job_id}/lyrics")
def get_lyrics(
    job_id: str,
    _: bool = Depends(require_token),
):
    job = get_job_or_404(job_id)

    if job["status"] != "completed":
        raise HTTPException(status_code=409, detail="Processing is not complete.")

    lyrics_path = Path(job["directory"]) / "lyrics.json"

    if not lyrics_path.is_file():
        raise HTTPException(status_code=404, detail="Lyrics file not found.")

    with open(lyrics_path, "r", encoding="utf-8") as source:
        return json.load(source)


@app.delete("/v1/jobs/{job_id}")
def delete_job(
    job_id: str,
    _: bool = Depends(require_token),
):
    with JOBS_LOCK:
        job = JOBS.get(job_id)

        if job is None:
            raise HTTPException(status_code=404, detail="Job not found.")

        if job["status"] == "processing":
            raise HTTPException(
                status_code=409,
                detail="Cannot delete a job while processing is active.",
            )

        directory = job["directory"]
        del JOBS[job_id]

    shutil.rmtree(directory, ignore_errors=True)

    return {"deleted": True, "job_id": job_id}
