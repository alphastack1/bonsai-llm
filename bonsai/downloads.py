"""Resumable downloads with exact length and SHA-256 verification."""
import hashlib
import os
import re
import threading
import time
from pathlib import Path

import requests


class Downloads:
    def __init__(self):
        self._lock = threading.RLock()
        self._jobs = {}

    def snapshot(self):
        with self._lock:
            return {key: dict(value) for key, value in self._jobs.items()}

    def update(self, key, **values):
        with self._lock:
            self._jobs.setdefault(key, {}).update(values)

    def busy(self, key):
        return self.snapshot().get(key, {}).get("status") in {
            "queued", "connecting", "downloading", "verifying", "extracting"
        }

    def start(self, key, operation):
        with self._lock:
            if self.busy(key):
                return False
            self._jobs[key] = {"status": "queued", "percent": 0, "error": None}

        def run():
            try:
                operation()
                self.update(key, status="done", percent=100, error=None)
            except Exception as error:
                self.update(key, status="error", error=str(error))

        threading.Thread(target=run, name=f"download-{key}", daemon=True).start()
        return True

    def fetch(self, artifact, destination, key, *, offset=0, total=None):
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        expected = artifact["bytes"]
        total = total or expected
        if destination.is_file() and destination.stat().st_size == expected:
            self.update(key, status="verifying", detail=destination.name)
            if sha256(destination) == artifact["sha256"]:
                return destination
        part = destination.with_name(destination.name + ".part")
        if part.exists() and part.stat().st_size > expected:
            part.unlink()
        # Keep partial data after network failures so the next attempt can resume.
        for attempt in range(4):
            done = part.stat().st_size if part.exists() else 0
            if done == expected:
                break
            self.update(key, status="connecting", detail=destination.name, error=None)
            try:
                headers = {"Range": f"bytes={done}-"} if done else {}
                with requests.get(artifact["url"], headers=headers, stream=True,
                                  timeout=(20, 60)) as response:
                    response.raise_for_status()
                    if done and response.status_code == 206:
                        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)",
                                             response.headers.get("Content-Range", ""))
                        if not match or int(match[1]) != done or int(match[3]) != expected:
                            raise ValueError("Server returned an invalid download range")
                    elif response.status_code == 200:
                        done = 0
                    else:
                        raise ValueError(f"Unexpected download response: {response.status_code}")
                    self.update(key, status="downloading", total_bytes=total)
                    with part.open("ab" if done else "wb") as output:
                        for chunk in response.iter_content(1024 * 1024):
                            if not chunk:
                                continue
                            done += len(chunk)
                            if done > expected:
                                raise ValueError("Download is larger than the published artifact")
                            output.write(chunk)
                            self.update(key, downloaded_bytes=offset + done,
                                        percent=min(99, int((offset + done) * 100 / total)))
                    if done != expected:
                        raise requests.ConnectionError("Download interrupted; retrying")
                break
            except requests.RequestException:
                if attempt == 3:
                    raise
                time.sleep(attempt + 1)
        self.update(key, status="verifying", detail=destination.name)
        if not part.is_file() or part.stat().st_size != expected:
            raise ValueError(f"Incorrect file size for {destination.name}")
        if sha256(part) != artifact["sha256"]:
            part.unlink()
            raise ValueError(f"SHA-256 check failed for {destination.name}. Please retry.")
        os.replace(part, destination)
        return destination


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
