"""Bonsai 2 desktop entry point. All inference stays on loopback."""
import argparse
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import sys
import threading
import webbrowser

from bonsai.engine import Engine
from bonsai.web import create_app


def paths():
    frozen = getattr(sys, "frozen", False)
    bundle = Path(sys._MEIPASS) if frozen else Path(__file__).resolve().parent
    if os.environ.get("BONSAI_DATA_DIR"):
        data = Path(os.environ["BONSAI_DATA_DIR"]).resolve()
    elif frozen:
        data = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Bonsai2"
    else:
        data = bundle
    return bundle, data


def main():
    parser = argparse.ArgumentParser(description="Bonsai 2 — local, offline AI")
    parser.add_argument("--browser", action="store_true", help="Open in your browser")
    parser.add_argument("--no-window", action="store_true", help="Run the local HTTP server only")
    parser.add_argument("--port", type=int, default=7860)
    args = parser.parse_args()
    bundle, data = paths()
    (data / "logs").mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(data / "logs/bonsai.log", maxBytes=2_000_000,
                                  backupCount=2, encoding="utf-8")
    logging.basicConfig(level=logging.INFO, handlers=[handler],
                        format="%(asctime)s %(levelname)s %(message)s")
    engine = Engine(bundle, data)
    app = create_app(engine)
    from waitress import create_server
    try:
        server = create_server(app, host="127.0.0.1", port=args.port, threads=8,
                               max_request_body_size=8 * 1024 * 1024, channel_timeout=600)
    except OSError:
        engine.close()
        raise SystemExit(f"Port {args.port} is in use. Close the other app or use --port.")
    url = f"http://127.0.0.1:{server.effective_port}"
    engine.bootstrap()
    try:
        if args.no_window:
            server.run()
        else:
            threading.Thread(target=server.run, name="bonsai-web", daemon=True).start()
            if args.browser:
                webbrowser.open(url)
                threading.Event().wait()
            else:
                import webview
                webview.create_window("Bonsai 2", url, width=1180, height=820,
                                      min_size=(390, 600), background_color="#f8f9f5")
                webview.start(gui="edgechromium", private_mode=False,
                              storage_path=str(data / "webview"))
    except KeyboardInterrupt:
        pass
    except Exception:
        logging.exception("Bonsai desktop window could not start")
        raise
    finally:
        engine.close()
        server.close()


if __name__ == "__main__":
    main()
