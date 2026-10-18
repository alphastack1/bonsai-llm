"""A same-origin HTTP API for the desktop UI; never a remote model provider."""
import base64
import json
import re

import requests
from flask import Flask, Response, jsonify, request, send_from_directory, stream_with_context


def chat_payload(body, vision):
    if not isinstance(body, dict):
        raise ValueError("Chat request must be an object")
    messages = body.get("messages")
    if not isinstance(messages, list) or not messages or len(messages) > 200:
        raise ValueError("Send between 1 and 200 messages")
    cleaned = []
    for message in messages:
        if not isinstance(message, dict) or message.get("role") not in {"system", "user", "assistant"}:
            raise ValueError("Invalid chat message")
        content = message.get("content")
        if isinstance(content, str):
            if len(content) > 100000:
                raise ValueError("Message is too long. Please start a new chat or shorten it.")
        elif isinstance(content, list) and message["role"] == "user":
            for part in content:
                if not isinstance(part, dict):
                    raise ValueError("Invalid image message")
                if part.get("type") == "text" and isinstance(part.get("text"), str):
                    if len(part["text"]) > 100000:
                        raise ValueError("Message is too long")
                elif part.get("type") == "image_url" and vision:
                    image = part.get("image_url", {})
                    url = image.get("url", "") if isinstance(image, dict) else ""
                    if not isinstance(url, str) or not re.fullmatch(
                            r"data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+", url):
                        raise ValueError("Use an attached image. Remote image URLs are not supported.")
                    try:
                        base64.b64decode(url.split(",", 1)[1], validate=True)
                    except ValueError:
                        raise ValueError("Invalid image data")
                else:
                    raise ValueError("Enable downloaded image support before attaching photos")
        else:
            raise ValueError("Invalid message content")
        cleaned.append({"role": message["role"], "content": content})
    thinking = body.get("thinking", False)
    if type(thinking) is not bool:
        raise ValueError("Thinking must be on or off")
    return {"model": "bonsai", "messages": cleaned, "stream": True,
            "stream_options": {"include_usage": True}, "max_tokens": 8192 if thinking else 2048,
            "temperature": 1.0 if thinking else 0.7, "top_p": 0.95 if thinking else 0.8,
            "top_k": 20, "min_p": 0, "presence_penalty": 1.0 if thinking else 1.5,
            "repeat_penalty": 1.0, "repeat_last_n": 64,
            "chat_template_kwargs": {"enable_thinking": thinking}}


def create_app(engine):
    app = Flask(__name__, static_folder=str(engine.bundle / "static"), static_url_path="/static")
    app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

    @app.before_request
    def local_only():
        host = request.host.split(":", 1)[0]
        if host not in {"127.0.0.1", "localhost"}:
            return jsonify(error="Local requests only"), 403
        if request.method != "GET":
            origin = request.headers.get("Origin")
            if origin and origin != request.host_url.rstrip("/"):
                return jsonify(error="Origin is not allowed"), 403
            if request.headers.get("Sec-Fetch-Site") == "cross-site":
                return jsonify(error="Origin is not allowed"), 403
            if not request.is_json:
                return jsonify(error="Send JSON"), 415
            if not isinstance(request.get_json(silent=True), dict):
                return jsonify(error="Send a JSON object"), 400

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; "
            "frame-ancestors 'none'; base-uri 'none'; object-src 'none'")
        if request.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.errorhandler(ValueError)
    def invalid(error):
        return jsonify(error=str(error)), 400

    @app.get("/")
    def index():
        return send_from_directory(engine.bundle / "static", "index.html")

    @app.get("/<path:path>")
    def assets(path):
        return send_from_directory(engine.bundle / "static", path)

    @app.get("/api/status")
    def status():
        return jsonify(engine.snapshot())

    @app.post("/api/setup/start")
    def setup():
        return jsonify(started=engine.setup())

    @app.post("/api/model/download")
    def download():
        body = request.get_json()
        return jsonify(started=engine.install_model(body.get("key"), body.get("vision") is True))

    @app.post("/api/model/load")
    def load():
        body = request.get_json()
        engine.load(body.get("key"), body.get("vision") is True)
        return jsonify(ok=True)

    @app.post("/api/model/delete")
    def delete():
        body = request.get_json()
        engine.delete_model(body.get("key"), body.get("vision") is True)
        return jsonify(ok=True)

    @app.post("/api/engine/download")
    def download_engine():
        return jsonify(started=engine.install_backend(request.get_json().get("backend")))

    @app.post("/api/settings")
    def settings():
        engine.load(engine.selected, engine.vision, request.get_json())
        return jsonify(ok=True)

    @app.post("/api/chat")
    def chat():
        payload = chat_payload(request.get_json(), engine.vision)
        if engine.state != "ready":
            return jsonify(error="Load a model before sending a message"), 409
        if not engine.chat_lock.acquire(blocking=False):
            return jsonify(error="Bonsai is busy. Stop the current answer and try again."), 409
        try:
            response = requests.post(engine.url + "/v1/chat/completions", json=payload,
                                     headers=engine.headers, stream=True, timeout=(10, 300))
            if not response.ok:
                try:
                    error = response.json().get("error", {})
                    detail = error.get("message", str(error)) if isinstance(error, dict) else str(error)
                except ValueError:
                    detail = "Local engine could not process this chat. Try a shorter conversation."
                response.close()
                engine.chat_lock.release()
                return jsonify(error=detail), 400
        except requests.RequestException:
            engine.chat_lock.release()
            return jsonify(error="The local engine is unavailable. Load the model again."), 503

        def stream():
            try:
                for line in response.iter_lines(chunk_size=1):
                    if line:
                        yield line + b"\n\n"
            except requests.RequestException:
                yield ("data: " + json.dumps({"error": {"message": "The local connection was interrupted."}})
                       + "\n\n").encode()
            finally:
                response.close()
                engine.chat_lock.release()

        return Response(stream_with_context(stream()), mimetype="text/event-stream",
                        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app
