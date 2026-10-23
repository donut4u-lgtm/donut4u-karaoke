# ============================================================
# AUDIO DOWNLOAD
# Allows limited redirects and validates audio content.
# Supports generic MIME types only when audio signature matches.
# ============================================================

def download_track(url, destination):
    current_url = validate_audio_url(url)
    total = 0

    audio_signatures = (
        b"ID3",       # MP3 with ID3 tag
        b"fLaC",      # FLAC
        b"OggS",      # OGG / OPUS
    )

    def has_audio_signature(data):
        if not data:
            return False

        # MP3 frame sync
        if (
            len(data) >= 2
            and data[0] == 0xFF
            and (data[1] & 0xE0) == 0xE0
        ):
            return True

        # MP3, FLAC, OGG, OPUS
        if data.startswith(audio_signatures):
            return True

        # WAV
        if (
            len(data) >= 12
            and data.startswith(b"RIFF")
            and data[8:12] == b"WAVE"
        ):
            return True

        # M4A / MP4 container
        if len(data) >= 12 and data[4:8] == b"ftyp":
            return True

        # AAC ADTS frame sync
        if (
            len(data) >= 2
            and data[0] == 0xFF
            and (data[1] & 0xF6) == 0xF0
        ):
            return True

        return False

    for redirect_count in range(MAX_REDIRECTS + 1):
        with httpx.stream(
            "GET",
            current_url,
            follow_redirects=False,
            timeout=httpx.Timeout(120.0, connect=10.0),
            headers={
                "User-Agent": "Donut4U-Karaoke/2.2.2",
                "Accept": "audio/*,application/octet-stream,*/*",
            },
        ) as response:

            if response.is_redirect:
                if redirect_count >= MAX_REDIRECTS:
                    raise ValueError(
                        "Audio URL exceeded the maximum number of redirects."
                    )

                location = response.headers.get("location")

                if not location:
                    raise ValueError(
                        "Audio redirect did not provide a destination."
                    )

                next_url = urljoin(current_url, location)

                # Signed audio URLs may not have a file extension.
                # Validate the destination before following it.
                current_url = validate_public_url(next_url)
                continue

            response.raise_for_status()

            content_type = response.headers.get(
                "content-type", ""
            ).split(";")[0].strip().lower()

            size = response.headers.get("content-length")

            if size:
                try:
                    content_length = int(size)
                except (TypeError, ValueError):
                    content_length = None

                if (
                    content_length is not None
                    and content_length > MAX_DOWNLOAD_BYTES
                ):
                    raise ValueError(
                        "Audio file exceeds the 100 MB limit."
                    )

            # Read the first chunk so we can verify the file signature
            # even when the host sends application/octet-stream or
            # an incorrect MIME type.
            chunks = response.iter_bytes(1024 * 1024)
            first_chunk = next(chunks, b"")

            if not first_chunk:
                raise ValueError(
                    "The downloaded audio file is empty."
                )

            if content_type not in AUDIO_CONTENT_TYPES:
                if not has_audio_signature(first_chunk[:64]):
                    raise ValueError(
                        "The server response is not recognized as an "
                        "audio file. Try another direct audio-file URL."
                    )

            # Even if the server claims it is audio, reject obvious
            # HTML or other unsupported content.
            if (
                content_type in AUDIO_CONTENT_TYPES
                and first_chunk.lstrip().lower().startswith(
                    (b"<!doctype html", b"<html", b"<head", b"<body")
                )
            ):
                raise ValueError(
                    "The URL returned a webpage instead of an audio file."
                )

            with open(destination, "wb") as output:
                total = len(first_chunk)

                if total > MAX_DOWNLOAD_BYTES:
                    raise ValueError(
                        "Audio file exceeds the 100 MB limit."
                    )

                output.write(first_chunk)

                for chunk in chunks:
                    if not chunk:
                        continue

                    total += len(chunk)

                    if total > MAX_DOWNLOAD_BYTES:
                        raise ValueError(
                            "Audio file exceeds the 100 MB limit."
                        )

                    output.write(chunk)

            break

    if total == 0:
        raise ValueError("The downloaded audio file is empty.")
