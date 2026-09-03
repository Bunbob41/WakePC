#!/usr/bin/env python3
"""Unit tests for the wakepc service. Run: python3 -m unittest -v test_wakepc"""

import importlib.util
import pathlib
import tempfile
import unittest

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


if __name__ == "__main__":
    unittest.main()
