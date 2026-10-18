"""Own the local inference process, downloads, and persisted engine settings."""
import atexit
import json
import logging
import os
import secrets
import shutil
import socket
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

import requests

from .catalog import read_catalog
from .downloads import Downloads, sha256
from .runtime import install_windows

log = logging.getLogger("bonsai")
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
DEFAULTS = {"threads": 0, "ctx_size": 16384, "backend": "auto"}


def validate_settings(value):
    if not isinstance(value, dict):
        raise ValueError("Settings must be an object")
    result = dict(DEFAULTS)
    for key, item in value.items():
        if key not in DEFAULTS:
            raise ValueError(f"Unknown engine setting: {key}")
        if key == "backend":
            if item not in {"auto", "cpu", "vulkan", "cuda"}:
                raise ValueError("Choose Auto, CPU, Vulkan, or CUDA")
        elif type(item) is not int:
            raise ValueError(f"{key} must be a whole number")
        elif key == "threads" and not 0 <= item <= 32:
            raise ValueError("Threads must be between 0 (automatic) and 32")
        elif key == "ctx_size" and item not in {4096, 8192, 16384, 32768}:
            raise ValueError("Context must be 4096, 8192, 16384, or 32768")
        result[key] = item
    return result


class Engine:
    def __init__(self, bundle: Path, data: Path):
        self.bundle, self.data = Path(bundle), Path(data)
        self.catalog = read_catalog(self.bundle)
        self.models_dir = self.data / "models"
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self.downloads = Downloads()
        self.settings = dict(DEFAULTS)
        self.selected = "2B"
        self.vision = False
        try:
            saved = json.loads((self.data / "settings.json").read_text())
            self.settings = validate_settings(saved.get("engine", {}))
            if saved.get("model") in self.catalog["models"]:
                self.selected = saved["model"]
            self.vision = bool(saved.get("vision", False))
        except (OSError, ValueError, TypeError):
            pass
        self.state, self.error = "stopped", None
        self.actual_backend = None
        self.process = None
        self._process_lock = threading.Lock()
        self._operation = threading.Lock()
        self.chat_lock = threading.Lock()
        self._closed = False
        self.tail = deque(maxlen=60)
        self.token = secrets.token_urlsafe(32)
        self.port = 0
        atexit.register(self.close)

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}"

    @property
    def headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def _save(self):
        part = self.data / "settings.json.part"
        part.write_text(json.dumps({"engine": self.settings, "model": self.selected,
                                    "vision": self.vision}, indent=2))
        os.replace(part, self.data / "settings.json")

    def model_info(self):
        result = {}
        for key, info in self.catalog["models"].items():
            result[key] = {"key": key, "name": info["name"], "label": info["label"],
                           "description": info["description"], "bundled": info["bundled"],
                           "bytes": info["model"]["bytes"],
                           "vision_bytes": info["projector"]["bytes"],
                           "installed": self._present(info["model"]),
                           "vision_installed": self._present(info["projector"])}
        return result

    def _present(self, artifact):
        path = self.models_dir / artifact["file"]
        return path.is_file() and path.stat().st_size == artifact["bytes"]

    def _binary(self, backend):
        for root in dict.fromkeys([self.data, self.bundle]):
            folder = root / "bin" / backend
            try:
                manifest = json.loads((folder / "release.json").read_text())
                binary = folder / "llama-server.exe"
                if manifest["tag"] == self.catalog["runtime"]["tag"] and binary.is_file():
                    return binary
            except (OSError, ValueError, KeyError):
                continue
        return None

    def snapshot(self):
        with self._process_lock:
            if self.state == "ready" and (not self.process or self.process.poll() is not None):
                self.state, self.error = "error", "The local engine stopped. Load the model again."
        return {"version": "3.0.0", "platform": "windows", "state": self.state,
                "ready": self.state == "ready", "error": self.error,
                "active_model": self.selected, "vision": self.vision,
                "backend": self.actual_backend,
                "engine_ready": any(self._binary(b) for b in ["cpu", "vulkan", "cuda"]),
                "available_backends": [b for b in ["cpu", "vulkan", "cuda"] if self._binary(b)],
                "runtime": self.catalog["runtime"]["tag"], "models": self.model_info(),
                "downloads": self.downloads.snapshot(), "settings": dict(self.settings)}

    def bootstrap(self):
        def work():
            try:
                artifact = self.catalog["models"]["2B"]["model"]
                bundled = self.bundle / "bundled" / artifact["file"]
                if not self._present(artifact) and bundled.is_file():
                    self.state = "installing"
                    self.downloads.update("model_2B", status="extracting", percent=0)
                    part = self.models_dir / (artifact["file"] + ".part")
                    shutil.copyfile(bundled, part)
                    if part.stat().st_size != artifact["bytes"] or sha256(part) != artifact["sha256"]:
                        raise ValueError("The included model is damaged. Please reinstall Bonsai.")
                    os.replace(part, self.models_dir / artifact["file"])
                    self.downloads.update("model_2B", status="done", percent=100)
                if not self.model_info()[self.selected]["installed"]:
                    self.selected, self.vision = "2B", False
                self.state = "stopped"
                if self.model_info()[self.selected]["installed"] and self.snapshot()["engine_ready"]:
                    self.load(self.selected, self.vision)
            except Exception as error:
                log.exception("First-run installation failed")
                self.state, self.error = "error", str(error)
        threading.Thread(target=work, name="bonsai-startup", daemon=True).start()

    def install_model(self, key, vision=False):
        if key not in self.catalog["models"]:
            raise ValueError("Unknown model")
        info = self.catalog["models"][key]
        job = f"vision_{key}" if vision else f"model_{key}"
        artifact = info["projector"] if vision else info["model"]
        return self.downloads.start(job, lambda: self.downloads.fetch(
            artifact, self.models_dir / artifact["file"], job))

    def setup(self):
        def work():
            for backend in ["cpu", "vulkan"]:
                if not self._binary(backend):
                    install_windows(self.catalog, backend, self.data, self.downloads, "setup")
            artifact = self.catalog["models"]["2B"]["model"]
            self.downloads.fetch(artifact, self.models_dir / artifact["file"], "setup")
            self.load("2B", False)
        return self.downloads.start("setup", work)

    def install_backend(self, backend):
        if backend not in {"cpu", "vulkan", "cuda"}:
            raise ValueError("Unknown backend")
        return self.downloads.start("engine", lambda: install_windows(
            self.catalog, backend, self.data, self.downloads))

    def load(self, key, vision=False, settings=None):
        if self._closed:
            raise ValueError("Bonsai is closing")
        if key not in self.catalog["models"]:
            raise ValueError("Unknown model")
        info = self.model_info()[key]
        if not info["installed"] or (vision and not info["vision_installed"]):
            raise ValueError("Download this model and its image support before loading it")
        proposed = validate_settings(settings) if settings is not None else dict(self.settings)
        if not self._operation.acquire(blocking=False):
            raise ValueError("A model is already loading")
        if not self.chat_lock.acquire(blocking=False):
            self._operation.release()
            raise ValueError("Stop the current answer before changing models")
        self.state, self.error = "loading", None

        def run():
            try:
                model = self.catalog["models"][key]
                for artifact in [model["model"]] + ([model["projector"]] if vision else []):
                    if sha256(self.models_dir / artifact["file"]) != artifact["sha256"]:
                        raise ValueError("Model verification failed. Delete it and download it again.")
                self._stop_process()
                backend = proposed["backend"]
                choices = ([b for b in ["cuda", "vulkan", "cpu"] if self._binary(b)]
                           if backend == "auto" else [backend])
                if not choices:
                    raise ValueError("Install the local runtime first")
                last_error = None
                for chosen in choices:
                    try:
                        self._launch(key, vision, proposed, chosen)
                        self.selected, self.vision = key, vision
                        self.settings, self.actual_backend = proposed, chosen
                        self._save()
                        self.state = "ready"
                        return
                    except Exception as error:
                        self._stop_process()
                        last_error = error
                        log.warning("%s engine: %s", chosen, error)
                raise last_error
            except Exception as error:
                self.state, self.error = "error", str(error)
                log.exception("Model loading failed")
            finally:
                self.chat_lock.release()
                self._operation.release()

        threading.Thread(target=run, name="bonsai-load", daemon=True).start()

    def _launch(self, key, vision, settings, backend):
        binary = self._binary(backend)
        if not binary:
            raise ValueError(f"The {backend} runtime is not installed")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = listener.getsockname()[1]
        model = self.catalog["models"][key]
        threads = settings["threads"] or min(6, max(2, (os.cpu_count() or 4) // 2))
        command = [str(binary), "-m", str(self.models_dir / model["model"]["file"]),
                   "--host", "127.0.0.1", "--port", str(self.port),
                   "--api-key", self.token, "--alias", "bonsai",
                   "--ctx-size", str(settings["ctx_size"]), "--threads", str(threads),
                   "--threads-batch", str(threads), "--parallel", "1", "--jinja",
                   "--n-gpu-layers", "0" if backend == "cpu" else "99",
                   "--flash-attn", "on", "--cache-type-k", "q8_0", "--cache-type-v", "q8_0",
                   "--reasoning-format", "deepseek", "--no-webui", "--offline"]
        if vision:
            command += ["--mmproj", str(self.models_dir / model["projector"]["file"])]
        self.tail.clear()
        env = dict(os.environ)
        env["PATH"] = str(binary.parent) + os.pathsep + env.get("PATH", "")
        process = subprocess.Popen(command, cwd=binary.parent, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                   errors="replace", creationflags=CREATE_NO_WINDOW)
        with self._process_lock:
            if self._closed:
                process.kill()
                raise ValueError("Bonsai is closing")
            self.process = process

        def read_output():
            for line in process.stdout:
                self.tail.append(line.strip())
                log.info("engine: %s", line.strip())
            process.stdout.close()
        threading.Thread(target=read_output, daemon=True, name="bonsai-engine-log").start()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline and not self._closed:
            if process.poll() is not None:
                details = "\n".join(self.tail)[-2200:]
                raise ValueError(f"Local engine exited ({process.returncode}). {details}")
            try:
                response = requests.get(self.url + "/health", headers=self.headers, timeout=1)
                if response.ok:
                    return
            except requests.RequestException:
                pass
            time.sleep(0.25)
        raise ValueError("Local engine did not become ready. Try a smaller context or the CPU backend.")

    def delete_model(self, key, vision=False):
        if key not in self.catalog["models"]:
            raise ValueError("Unknown model")
        if self.downloads.busy(f"model_{key}") or self.downloads.busy(f"vision_{key}"):
            raise ValueError("Wait for the download to finish")
        if self._operation.locked():
            raise ValueError("Wait for the current model to finish loading")
        if key == self.selected and self.state in {"ready", "loading"}:
            raise ValueError("Switch to another model before deleting this one")
        artifact = self.catalog["models"][key]["projector" if vision else "model"]
        for suffix in ["", ".part"]:
            (self.models_dir / (artifact["file"] + suffix)).unlink(missing_ok=True)

    def _stop_process(self):
        with self._process_lock:
            process, self.process = self.process, None
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def close(self):
        self._closed = True
        self._stop_process()
