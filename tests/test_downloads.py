import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from bonsai.downloads import Downloads


class Reply:
    def __init__(self, data, status=200, headers=None):
        self.data, self.status_code, self.headers = data, status, headers or {}
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def raise_for_status(self): pass
    def iter_content(self, chunk_size): yield self.data


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "model.gguf"
        self.data = b"GGUF-test-verified-artifact"
        self.artifact = {"bytes": len(self.data), "sha256": hashlib.sha256(self.data).hexdigest(), "url": "https://example.invalid/model"}
        self.downloads = Downloads()

    def test_resumes_and_only_publishes_verified_bytes(self):
        self.path.with_suffix(".gguf.part").write_bytes(self.data[:5])
        with patch("bonsai.downloads.requests.get", return_value=Reply(self.data[5:], 206,
                   {"Content-Range": f"bytes 5-{len(self.data)-1}/{len(self.data)}"})) as get:
            self.downloads.fetch(self.artifact, self.path, "test")
        self.assertEqual(get.call_args.kwargs["headers"], {"Range": "bytes=5-"})
        self.assertEqual(self.path.read_bytes(), self.data)
        self.assertFalse(self.path.with_suffix(".gguf.part").exists())

    def test_server_ignoring_range_restarts_instead_of_appending(self):
        self.path.with_suffix(".gguf.part").write_bytes(self.data[:5])
        with patch("bonsai.downloads.requests.get", return_value=Reply(self.data)):
            self.downloads.fetch(self.artifact, self.path, "test")
        self.assertEqual(self.path.read_bytes(), self.data)

    def test_corrupt_download_is_not_accepted(self):
        with patch("bonsai.downloads.requests.get", return_value=Reply(b"x" * len(self.data))):
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                self.downloads.fetch(self.artifact, self.path, "test")
        self.assertFalse(self.path.exists())
        self.assertFalse(self.path.with_suffix(".gguf.part").exists())

    def test_invalid_resume_range_does_not_modify_existing_partial(self):
        part = self.path.with_suffix(".gguf.part")
        part.write_bytes(self.data[:5])
        with patch("bonsai.downloads.requests.get", return_value=Reply(self.data[5:], 206,
                   {"Content-Range": f"bytes 0-{len(self.data)-1}/{len(self.data)}"})):
            with self.assertRaisesRegex(ValueError, "range"):
                self.downloads.fetch(self.artifact, self.path, "test")
        self.assertEqual(part.read_bytes(), self.data[:5])

    def test_downloaded_file_can_be_verified_without_network(self):
        self.path.write_bytes(self.data)
        with patch("bonsai.downloads.requests.get", side_effect=AssertionError("Network is offline")):
            self.downloads.fetch(self.artifact, self.path, "test")


if __name__ == "__main__": unittest.main()
