"""Exercise renewal behavior without contacting deSEC or issuing certificates."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


def bash_path(path):
    value = Path(path).resolve().as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


class RenewalTest(unittest.TestCase):
    def setUp(self):
        self.bash = os.environ.get("BASH_EXE") or shutil.which("bash")
        if not self.bash:
            self.skipTest("Bash is required")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("home", "bin", "tmp"):
            (self.root / name).mkdir()
        self.live = self.root / "certbot/conf/live/mirror.example.com"
        self.live.mkdir(parents=True)
        (self.live / "fullchain.pem").write_text("mock certificate")
        (self.live / "privkey.pem").write_text("mock key")
        mock = self.root / "bin/certbot"
        mock.write_text(
            '#!/usr/bin/env bash\n'
            'printf "%s\\n" "$@" > "$MOCK_ARGS"\n'
            'exit "${MOCK_EXIT:-0}"\n', encoding="utf-8"
        )
        mock.chmod(0o700)
        self.env = os.environ.copy()
        for name in ("DESEC_TOKEN", "DESC_TOKEN"):
            self.env.pop(name, None)
        self.env.update({
            "NAVMIRROR_DOMAIN": "mirror.example.com",
            "HOME": bash_path(self.root / "home"),
            "TMPDIR": bash_path(self.root / "tmp"),
            "CERTBOT_HOME": bash_path(self.root / "certbot"),
            "CERT_OUT": bash_path(self.root / "out"),
            "MOCK_BIN": bash_path(self.root / "bin"),
            "MOCK_ARGS": bash_path(self.root / "args"),
            "SCRIPT": bash_path(Path(__file__).resolve().parents[1] / "renew_cert.sh"),
        })

    def run_script(self):
        return subprocess.run(
            [self.bash, "-c", 'export PATH="$MOCK_BIN:$PATH"; exec bash "$SCRIPT"'],
            env=self.env, capture_output=True, text=True, encoding="utf-8", timeout=30,
        )

    def test_unset_environment_uses_token_file_and_cleans_credentials(self):
        (self.root / "home/.desec-token").write_text("test-placeholder")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "out/tesla_key.pem").read_text(), "mock key")
        args = (self.root / "args").read_text().splitlines()
        self.assertIn("--cert-name", args)
        self.assertIn("mirror.example.com", args)
        self.assertNotIn("--force-renewal", args)
        self.assertEqual(list((self.root / "tmp").glob("navmirror-desec.*")), [])

    def test_certbot_failure_does_not_copy_old_certificates(self):
        self.env.update(DESEC_TOKEN="test-placeholder", MOCK_EXIT="42")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "out/tesla_key.pem").exists())
        self.assertEqual(list((self.root / "tmp").glob("navmirror-desec.*")), [])

    def test_missing_token_reports_error_without_unbound_variable(self):
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("unbound variable", result.stderr)
        self.assertFalse((self.root / "args").exists())

    def test_legacy_environment_token_still_works(self):
        self.env["DESC_TOKEN"] = "test-placeholder"
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_initial_certificate_stops_before_certbot(self):
        self.env["DESEC_TOKEN"] = "test-placeholder"
        (self.live / "privkey.pem").unlink()
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "args").exists())
        self.assertEqual(list((self.root / "tmp").glob("navmirror-desec.*")), [])


if __name__ == "__main__":
    unittest.main()
