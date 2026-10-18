import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from bonsai.engine import validate_settings
from bonsai.web import chat_payload, create_app


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        (root / "static").mkdir()
        (root / "static/index.html").write_text("Bonsai test")
        self.client = create_app(SimpleNamespace(bundle=root, snapshot=lambda: {"ready":False})).test_client()

    def test_cross_site_mutations_are_rejected(self):
        response = self.client.post("/api/model/load", json={"key":"2B"},
                                    headers={"Origin":"https://external.invalid"})
        self.assertEqual(response.status_code,403)

    def test_dns_rebinding_host_is_rejected(self):
        self.assertEqual(self.client.get("/api/status",headers={"Host":"attacker.invalid"}).status_code,403)

    def test_malformed_action_body_is_a_user_error(self):
        self.assertEqual(self.client.post("/api/model/load",json=[]).status_code,400)

    def test_remote_images_cannot_trigger_network_requests(self):
        with self.assertRaisesRegex(ValueError,"Remote image"):
            chat_payload({"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://external.invalid/photo.png"}}]}]},True)

    def test_local_images_require_enabled_vision(self):
        with self.assertRaisesRegex(ValueError,"image support"):
            chat_payload({"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,YQ=="}}]}]},False)

    def test_model_sampling_follows_mode_and_never_uses_cloud(self):
        body={"messages":[{"role":"user","content":"hello"}]}
        fast=chat_payload(body,False)
        thinking=chat_payload({**body,"thinking":True},False)
        self.assertFalse(fast["chat_template_kwargs"]["enable_thinking"])
        self.assertTrue(thinking["chat_template_kwargs"]["enable_thinking"])
        self.assertEqual(thinking["max_tokens"],8192)
        self.assertEqual(fast["presence_penalty"],1.5)
        self.assertEqual(fast["model"],"bonsai")

    def test_invalid_settings_are_rejected(self):
        for invalid in [{"threads":True},{"threads":-1},{"ctx_size":9999999},{"backend":"cloud"},{"extra":"ignored?"}]:
            with self.subTest(invalid=invalid),self.assertRaises(ValueError):validate_settings(invalid)

    def test_static_files_cannot_escape_asset_directory(self):
        self.assertEqual(self.client.get("/../settings.json").status_code,404)


if __name__ == "__main__": unittest.main()
