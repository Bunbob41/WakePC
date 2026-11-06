#!/usr/bin/env python3
"""Unit tests for the wakepc service. Run: python3 -m unittest -v test_wakepc"""

import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

# wakepc.py exits at import time if /etc/wakepc.conf is missing, so load it as a
# module without running main() and point CONFIG_PATH at a temp file per test.
spec = importlib.util.spec_from_file_location(
    "wakepc", pathlib.Path(__file__).with_name("wakepc.py")
)
wakepc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wakepc)


def write_config(text):
    handle = tempfile.NamedTemporaryFile("w", suffix=".conf", delete=False)
    handle.write(text)
    handle.close()
    wakepc.CONFIG_PATH = handle.name
    return handle.name


VALID = """
[wakepc]
token = test-token
bind_host = 100.64.0.2
port = 8787

[command:wake-pc]
run = WOL AA:BB:CC:DD:EE:FF
ping = 192.168.1.50
broadcast_ip = 192.168.1.255

[command:reboot-pi]
run = shell sudo /usr/sbin/reboot
"""


class ConfigTest(unittest.TestCase):

    def test_parses_commands_and_runners(self):
        write_config(VALID)
        config = wakepc.load_config()
        self.assertEqual(config["token"], "test-token")
        self.assertEqual(config["port"], 8787)
        self.assertEqual(sorted(config["commands"]), ["reboot-pi", "wake-pc"])

        wake = config["commands"]["wake-pc"]
        # MAC is normalised to bare lowercase hex for the magic packet.
        self.assertEqual(wake.run, ("wol", "aabbccddeeff"))
        self.assertEqual(wake.ping, "192.168.1.50")
        self.assertEqual(wake.broadcast_ip, "192.168.1.255")

        reboot = config["commands"]["reboot-pi"]
        self.assertEqual(reboot.run, ("shell", "sudo /usr/sbin/reboot"))
        self.assertIsNone(reboot.ping)

    def test_reads_a_config_written_with_a_bom(self):
        # PowerShell and Notepad both write UTF-8 with a BOM; configparser
        # would otherwise see it as part of the first line and find no
        # section header at all.
        handle = tempfile.NamedTemporaryFile("w", suffix=".conf", delete=False, encoding="utf-8-sig")
        handle.write(VALID)
        handle.close()
        wakepc.CONFIG_PATH = handle.name
        self.assertEqual(wakepc.load_config()["token"], "test-token")

    def test_rejects_bad_configs(self):
        for label, text in [
            ("short token", VALID.replace("token = test-token", "token = 65")),
            ("bad mac", VALID.replace("WOL AA:BB:CC:DD:EE:FF", "WOL nonsense")),
            ("unknown runner", VALID.replace("run = WOL AA:BB:CC:DD:EE:FF", "run = rm -rf /")),
            ("bad command name", VALID.replace("[command:wake-pc]", "[command:Wake PC!]")),
            ("no commands", VALID.split("[command:")[0]),
        ]:
            with self.subTest(label):
                write_config(text)
                with self.assertRaises(SystemExit):
                    wakepc.load_config()


class AuthTest(unittest.TestCase):

    def test_a_tailnet_relay_needs_no_token_at_all(self):
        # The point of the identity path: nothing to type, nothing to leak.
        write_config(VALID.replace("token = test-token", ""))
        config = wakepc.load_config()
        self.assertEqual(config["token"], "")
        self.assertTrue(config["trust_tailnet"])

    def test_turning_identity_off_makes_a_token_mandatory(self):
        write_config(VALID.replace("token = test-token", "trust_tailnet = no"))
        with self.assertRaises(SystemExit):
            wakepc.load_config()

    def test_allow_users_is_parsed_as_a_lowercased_set(self):
        write_config(VALID.replace("token = test-token", "allow_users = You@Example.com, other@example.com"))
        self.assertEqual(
            wakepc.load_config()["allow_users"],
            {"you@example.com", "other@example.com"},
        )

    def test_only_the_relay_decides_what_is_elevated(self):
        write_config(VALID)
        commands = wakepc.load_config()["commands"]
        # A magic packet can only turn something on.
        self.assertFalse(commands["wake-pc"].confirm)
        # This one actually reboots the machine, whatever it is called.
        self.assertTrue(commands["reboot-pi"].confirm)

    def test_an_unrecognised_shell_command_can_be_marked_by_hand(self):
        write_config(VALID.replace(
            "run = shell sudo /usr/sbin/reboot",
            "run = shell /usr/local/bin/goodnight" + chr(10) + "confirm = yes",
        ))
        self.assertTrue(wakepc.load_config()["commands"]["reboot-pi"].confirm)

    def test_a_confirm_code_must_be_digits(self):
        write_config(VALID.replace("port = 8787", "port = 8787" + chr(10) + "confirm_code = hunter2"))
        with self.assertRaises(SystemExit):
            wakepc.load_config()

    def test_only_tailnet_addresses_are_ever_treated_as_peers(self):
        for addr, expected in [
            ("100.64.0.2", True),
            ("100.127.255.254", True),
            ("192.168.1.50", False),
            ("8.8.8.8", False),
            ("127.0.0.1", False),
        ]:
            with self.subTest(addr):
                parsed = wakepc.ipaddress.ip_address(addr)
                inside = any(parsed in net for net in wakepc.TAILNET_NETS)
                self.assertEqual(inside, expected)


class MagicPacketTest(unittest.TestCase):

    def test_packet_is_six_ff_bytes_then_the_mac_sixteen_times(self):
        sent = []
        original = wakepc.socket.socket

        class FakeSocket:
            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *args):
                return False

            def setsockopt(self_inner, *args):
                pass

            def sendto(self_inner, payload, addr):
                sent.append((payload, addr))

        wakepc.socket.socket = lambda *a, **k: FakeSocket()
        try:
            wakepc.send_magic_packet("aabbccddeeff", "192.168.1.255")
        finally:
            wakepc.socket.socket = original

        self.assertEqual(len(sent), 3, "packet is sent three times (fire-and-forget UDP)")
        payload, addr = sent[0]
        self.assertEqual(addr, ("192.168.1.255", 9))
        self.assertEqual(len(payload), 6 + 16 * 6)
        self.assertEqual(payload[:6], b"\xff" * 6)
        self.assertEqual(payload[6:12], bytes.fromhex("aabbccddeeff"))
        self.assertEqual(payload[-6:], bytes.fromhex("aabbccddeeff"))

    def test_rejects_a_malformed_mac(self):
        with self.assertRaises(ValueError):
            wakepc.send_magic_packet("nothex", "255.255.255.255")


class PassphraseTest(unittest.TestCase):

    def test_shape_and_variety(self):
        phrase = wakepc.generate_passphrase(5)
        self.assertEqual(len(phrase.split("-")), 5)
        self.assertTrue(all(word in wakepc.WORDS for word in phrase.split("-")))
        # Not a serious entropy check, just a guard against a constant generator.
        self.assertGreater(len({wakepc.generate_passphrase(4) for _ in range(20)}), 15)


# A relay that identifies callers by token rather than by Tailscale, so these
# tests need no tailnet, listening on loopback only.
LOOPBACK = VALID.replace("bind_host = 100.64.0.2", "bind_host = 127.0.0.1").replace(
    "token = test-token", "token = test-token\ntrust_tailnet = no"
)


class SetupQrRemovedTest(unittest.TestCase):
    """The setup QR went in 0.14.0. Once tokens went away it only filled in an
    address, which is quicker to type, and it was the app's one closed-source
    dependency. These guard against it coming back half-wired."""

    @classmethod
    def setUpClass(cls):
        write_config(LOOPBACK)
        wakepc.Handler.config = wakepc.load_config()
        wakepc.Handler.log_message = lambda *args: None
        cls.server = wakepc.ThreadingHTTPServer(("127.0.0.1", 0), wakepc.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def get(self, path):
        request = urllib.request.Request(
            self.base + path, headers={"Authorization": "Bearer test-token"}
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read()

    def test_the_qr_endpoint_is_gone(self):
        status, body = self.get("/qr.png")
        self.assertEqual(status, 404)
        self.assertEqual(json.loads(body), {"error": "not found"})

    def test_the_panel_offers_no_qr(self):
        status, body = self.get("/")
        self.assertEqual(status, 200)
        page = body.decode().lower()
        self.assertNotIn("qr", page)
        self.assertIn("tailscale name or address", page)

    def test_the_qr_command_is_unknown(self):
        done = subprocess.run(
            [sys.executable, str(pathlib.Path(__file__).with_name("wakepc.py")), "qr"],
            capture_output=True,
            text=True,
            timeout=30,
            env={**os.environ, "WAKEPC_CONFIG": write_config(LOOPBACK)},
        )
        self.assertNotEqual(done.returncode, 0)
        self.assertTrue(done.stderr.startswith("unknown command: qr"), done.stderr)
        self.assertNotIn("qr", done.stderr.split("\n", 1)[1])


if __name__ == "__main__":
    unittest.main()
