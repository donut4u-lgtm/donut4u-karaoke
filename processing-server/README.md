
# Donut4U Karaoke Processing Server

This service is designed to support online song search, vocal separation,
and lyric transcription for the Donut4U Karaoke Android app.

## Requirements

- Python 3.11
- Docker
- FFmpeg
- A Jamendo developer account and client ID
- Hosting that can run audio-processing models

## Configuration

Configure these environment variables in the hosting service:

- DONUT4U_API_TOKEN
- JAMENDO_CLIENT_ID
- JOB_TTL_MINUTES
- MAX_TRACK_SECONDS
- WHISPER_MODEL
- DEMUCS_MODEL
- JOB_ROOT

Do not commit real API tokens or credentials to GitHub.

## Intended API

- GET /health
- GET /v1/search?q=search-term
- POST /v1/jobs/from-track/{track_id}
- GET /v1/jobs/{job_id}
- GET /v1/jobs/{job_id}/instrumental
- GET /v1/jobs/{job_id}/lyrics
- DELETE /v1/jobs/{job_id}

## Important

These endpoints are planned interfaces. They will not work until main.py
has been implemented, deployed, and tested.

Only process music that the provider permits you to download and process.
A catalog listing does not automatically grant permission to use every
track for every purpose.

Audio processing can require substantial CPU, memory, and storage.
Test resource usage before making the service public.
