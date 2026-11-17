#!/usr/bin/env python3
"""Unit tests for the wakepc service. Run: python3 -m unittest -v test_wakepc"""

import importlib.util
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest import mock

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
run = WOL 3C:7C:3F:12:34:56
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
        self.assertEqual(wake.run, ("wol", "3c7c3f123456"))
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
            ("bad mac", VALID.replace("WOL 3C:7C:3F:12:34:56", "WOL nonsense")),
            ("unknown runner", VALID.replace("run = WOL 3C:7C:3F:12:34:56", "run = rm -rf /")),
            ("bad command name", VALID.replace("[command:wake-pc]", "[command:Wake PC!]")),
            ("ready not a number", VALID.replace("ping = 192.168.1.50", "ping = 192.168.1.50\nready = smb")),
            ("ready out of range", VALID.replace("ping = 192.168.1.50", "ping = 192.168.1.50\nready = 70000")),
            ("ready without ping", VALID.replace("run = shell sudo /usr/sbin/reboot", "run = shell sudo /usr/sbin/reboot\nready = 445")),
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


class ReadyTest(unittest.TestCase):
    """A machine answers ping well before it is usable. A command can name a
    TCP port (`ready = 445`), and /status then says whether that port answers
    as well. These run a real relay and a real listener on loopback."""

    @classmethod
    def setUpClass(cls):
        cls.listener = socket.socket()
        cls.listener.bind(("127.0.0.1", 0))
        cls.listener.listen(8)
        cls.open_port = cls.listener.getsockname()[1]
        # A port that was just free: bind, note it, close, so nothing listens.
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        cls.closed_port = probe.getsockname()[1]
        probe.close()

        config = LOOPBACK.replace("ping = 192.168.1.50", "ping = 127.0.0.1")
        config += (
            f"\n[command:wake-open]\nrun = WOL AA:BB:CC:DD:EE:01\nping = 127.0.0.1\nready = {cls.open_port}\n"
            f"\n[command:wake-closed]\nrun = WOL AA:BB:CC:DD:EE:02\nping = 127.0.0.1\nready = {cls.closed_port}\n"
            "\n[command:wake-asleep]\nrun = WOL AA:BB:CC:DD:EE:03\nping = 127.0.0.1\n"
            f"ready = {cls.open_port}\n"
        )
        write_config(config)
        wakepc.Handler.config = wakepc.load_config()
        wakepc.Handler.log_message = lambda *args: None
        cls.server = wakepc.ThreadingHTTPServer(("127.0.0.1", 0), wakepc.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.listener.close()

    def status(self, name):
        request = urllib.request.Request(
            f"{self.base}/status/{name}", headers={"Authorization": "Bearer test-token"}
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read())

    def test_the_ready_port_is_parsed(self):
        commands = wakepc.Handler.config["commands"]
        self.assertEqual(commands["wake-open"].ready, self.open_port)
        self.assertIsNone(commands["wake-pc"].ready)

    def test_awake_with_the_port_answering_is_ready(self):
        body = self.status("wake-open")
        self.assertTrue(body["awake"], body)
        self.assertIs(body["ready"], True, body)

    def test_awake_with_the_port_shut_is_not_ready(self):
        body = self.status("wake-closed")
        self.assertTrue(body["awake"], body)
        self.assertIs(body["ready"], False, body)

    def test_asleep_is_never_ready_even_if_the_port_would_answer(self):
        # The port really does answer here (checked first), and only ping is
        # made to fail, so this proves the relay does not try the port at all
        # when the machine is asleep, rather than trying a port that is shut.
        self.assertTrue(wakepc.probe_port("127.0.0.1", self.open_port))
        asleep = {"awake": False, "sent": 1, "loss_pct": 100.0}
        with mock.patch.object(wakepc, "probe_ping", return_value=asleep):
            body = self.status("wake-asleep")
        self.assertFalse(body["awake"], body)
        self.assertIs(body["ready"], False, body)

    def test_without_a_ready_port_the_reply_is_unchanged(self):
        # Older apps and machines with no `ready` see exactly what they did.
        body = self.status("wake-pc")
        self.assertTrue(body["awake"], body)
        self.assertNotIn("ready", body)

    def test_the_panel_shows_up_and_ready(self):
        request = urllib.request.Request(self.base + "/")
        with urllib.request.urlopen(request, timeout=5) as response:
            page = response.read().decode()
        self.assertIn("'READY'", page)
        self.assertIn("'UP'", page)



class FreshRelayTest(unittest.TestCase):
    """A relay with nothing configured is the normal starting point now: the
    phone adds the first machine, so setup needs no config editing at all."""

    def test_a_config_with_no_commands_loads(self):
        write_config(VALID.split("[command:")[0])
        config = wakepc.load_config()
        self.assertEqual(config["commands"], {})
        self.assertTrue(config["phone_setup"])

    def test_the_example_mac_is_ignored_rather_than_offered(self):
        # An unedited example used to give the app a wake button that woke nothing.
        write_config(VALID.replace("3C:7C:3F:12:34:56", "AA:BB:CC:DD:EE:FF"))
        self.assertEqual(sorted(wakepc.load_config()["commands"]), ["reboot-pi"])

    def test_the_shipped_example_config_loads(self):
        wakepc.CONFIG_PATH = str(pathlib.Path(__file__).with_name("wakepc.conf.example"))
        self.assertEqual(wakepc.load_config()["commands"], {})


LAN = [wakepc.ipaddress.ip_interface("192.168.1.10/24")]


class MachineFieldsTest(unittest.TestCase):

    def setUp(self):
        patcher = mock.patch.object(wakepc, "local_networks", return_value=LAN)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_a_mac_is_accepted_however_it_is_written(self):
        for written in ("3C:7C:3F:12:34:56", "3c-7c-3f-12-34-56", "3c7c.3f12.3456", " 3C7C3F123456 "):
            self.assertEqual(wakepc.clean_mac(written.strip()), "3c7c3f123456")

    def test_macs_that_cannot_be_a_machine_are_refused(self):
        for bad in ("nonsense", "AA:BB:CC:DD:EE:FF", "FF:FF:FF:FF:FF:FF", "01:00:5E:00:00:01",
                    "00:00:00:00:00:00", "3C:7C:3F:12:34", None, 12):
            with self.subTest(bad), self.assertRaises(wakepc.BadRequest):
                wakepc.clean_mac(bad)

    def test_only_home_network_addresses_can_be_probed(self):
        # The relay pings and port-checks a machine's address on request, so an
        # address off the LAN would turn a machine entry into a remote scanner.
        self.assertEqual(wakepc.clean_lan_ip("192.168.1.50", "ip"), "192.168.1.50")
        for bad in ("8.8.8.8", "127.0.0.1", "100.64.0.2", "169.254.1.1", "0.0.0.0", "::1", "pc.local"):
            with self.subTest(bad), self.assertRaises(wakepc.BadRequest):
                wakepc.clean_lan_ip(bad, "ip")

    def test_the_broadcast_address_is_worked_out(self):
        fields = wakepc.validate_machine({"name": "Desk PC", "mac": "3C:7C:3F:12:34:56", "ip": "192.168.1.50"})
        self.assertEqual(fields["broadcast_ip"], "192.168.1.255")
        self.assertIsNone(fields["ready"])

    def test_bad_fields_say_what_is_wrong(self):
        good = {"name": "Desk PC", "mac": "3C:7C:3F:12:34:56", "ip": "192.168.1.50"}
        for label, change in [
            ("no name", {"name": "  "}),
            ("long name", {"name": "x" * 41}),
            ("ready not a port", {"ready": "smb"}),
            ("ready out of range", {"ready": 70000}),
            ("ready without ip", {"ip": None, "ready": 445}),
            ("bad broadcast", {"broadcast_ip": "everyone"}),
        ]:
            with self.subTest(label), self.assertRaises(wakepc.BadRequest):
                wakepc.validate_machine({**good, **change})
        with self.assertRaises(wakepc.BadRequest):
            wakepc.validate_machine({"name": "no mac"})

    def test_a_name_becomes_a_usable_id(self):
        self.assertEqual(wakepc.slug("Desk PC!"), "desk-pc")
        self.assertEqual(wakepc.slug("___"), "pc")


class MachineStoreTest(unittest.TestCase):

    def setUp(self):
        patcher = mock.patch.object(wakepc, "local_networks", return_value=LAN)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.path = os.path.join(tempfile.mkdtemp(), "state", "machines.json")

    def fields(self, name="Desk PC", mac="3C:7C:3F:12:34:56"):
        return wakepc.validate_machine({"name": name, "mac": mac, "ip": "192.168.1.50", "ready": 445})

    def test_machines_survive_a_restart(self):
        added = wakepc.MachineStore(self.path).add(self.fields())
        self.assertEqual(added["id"], "desk-pc")
        again = wakepc.MachineStore(self.path)
        self.assertEqual(again.all(), [added])
        self.assertEqual(again.get("desk-pc")["ready"], 445)

    def test_ids_never_collide_with_each_other_or_the_config(self):
        store = wakepc.MachineStore(self.path, reserved={"wake-pc"})
        first = store.add(self.fields("PC"))
        second = store.add(self.fields("pc", mac="3C:7C:3F:12:34:57"))
        # wake-pc is a command in the config, so no machine may be called "pc".
        self.assertEqual([first["id"], second["id"]], ["pc-2", "pc-3"])

    def test_the_same_machine_is_not_added_twice(self):
        store = wakepc.MachineStore(self.path)
        store.add(self.fields())
        with self.assertRaises(wakepc.BadRequest):
            store.add(self.fields("again"))

    def test_update_and_remove(self):
        store = wakepc.MachineStore(self.path)
        machine = store.add(self.fields())
        changed = store.update("desk-pc", wakepc.validate_machine({"name": "Tower"}, base=machine))
        self.assertEqual((changed["id"], changed["name"], changed["mac"]), ("desk-pc", "Tower", "3c7c3f123456"))
        self.assertTrue(store.remove("desk-pc"))
        self.assertFalse(store.remove("desk-pc"))
        self.assertEqual(wakepc.MachineStore(self.path).all(), [])

    def test_a_failed_save_is_not_remembered(self):
        store = wakepc.MachineStore(self.path)
        with mock.patch.object(wakepc.os, "replace", side_effect=OSError("read-only")):
            with self.assertRaises(wakepc.StorageError):
                store.add(self.fields())
        self.assertEqual(store.all(), [])

    def test_a_damaged_file_does_not_stop_the_relay(self):
        os.makedirs(os.path.dirname(self.path))
        with open(self.path, "w") as handle:
            handle.write("{not json")
        with mock.patch("builtins.print"):
            self.assertEqual(wakepc.MachineStore(self.path).all(), [])

    def test_a_hand_edited_file_cannot_crash_or_cheat(self):
        os.makedirs(os.path.dirname(self.path))
        good = {"id": "desk-pc", "name": "Desk PC", "mac": "3c7c3f123456", "ip": "192.168.1.50",
                "ready": 445, "broadcast_ip": "192.168.1.255"}
        for content in ({"machines": 5}, {"machines": None}, [1, 2], "text"):
            with open(self.path, "w") as handle:
                json.dump(content, handle)
            self.assertEqual(wakepc.MachineStore(self.path).all(), [])
        entries = [
            good,
            {**good, "id": "copy"},                      # same MAC twice
            {**good, "id": "odd", "mac": "3c7c3f123457", "ready": "²"},
            {**good, "id": "pc", "mac": "3c7c3f123458"},  # would shadow the config's wake-pc
            "not an object",
        ]
        with open(self.path, "w") as handle:
            json.dump({"machines": entries}, handle)
        store = wakepc.MachineStore(self.path, reserved={"wake-pc"})
        self.assertEqual([m["id"] for m in store.all()], ["desk-pc"])

    def test_a_machine_is_also_a_command_older_apps_can_press(self):
        write_config(VALID)
        config = wakepc.load_config()
        store = wakepc.MachineStore(None, reserved=config["commands"])
        store.add(self.fields())
        commands = wakepc.all_commands(config, store)
        self.assertEqual(sorted(commands), ["reboot-pi", "wake-desk-pc", "wake-pc"])
        cmd = commands["wake-desk-pc"]
        self.assertEqual((cmd.run, cmd.ping, cmd.ready, cmd.confirm),
                         (("wol", "3c7c3f123456"), "192.168.1.50", 445, False))


class ScanTest(unittest.TestCase):

    def test_linux_neighbours(self):
        text = (
            "192.168.1.1 dev eth0 lladdr 3c:7c:3f:00:00:01 REACHABLE\n"
            "192.168.1.50 dev eth0 lladdr 3C:7C:3F:12:34:56 STALE\n"
            "192.168.1.77 dev eth0  FAILED\n"
            "192.168.1.78 dev eth0  INCOMPLETE\n"
        )
        self.assertEqual(wakepc.parse_neighbours(text),
                         [("192.168.1.1", "3c7c3f000001"), ("192.168.1.50", "3c7c3f123456")])

    def test_windows_neighbours(self):
        text = (
            "\nInterface: 192.168.1.10 --- 0xb\n"
            "  Internet Address      Physical Address      Type\n"
            "  192.168.1.1           3c-7c-3f-00-00-01     dynamic\n"
            "  192.168.1.255         ff-ff-ff-ff-ff-ff     static\n"
            "  224.0.0.22            01-00-5e-00-00-16     static\n"
        )
        self.assertEqual(wakepc.parse_neighbours(text), [("192.168.1.1", "3c7c3f000001")])

    def test_macos_neighbours(self):
        text = (
            "? (192.168.1.1) at 3c:7c:3f:0:0:1 on en0 ifscope [ethernet]\n"
            "? (192.168.1.9) at (incomplete) on en0 ifscope [ethernet]\n"
        )
        self.assertEqual(wakepc.parse_neighbours(text), [("192.168.1.1", "3c7c3f000001")])

    def test_only_real_lan_interfaces_are_scanned(self):
        text = json.dumps([
            {"ifname": "lo", "addr_info": [{"local": "127.0.0.1", "prefixlen": 8}]},
            {"ifname": "eth0", "addr_info": [{"local": "192.168.1.10", "prefixlen": 24}]},
            {"ifname": "tailscale0", "addr_info": [{"local": "100.64.0.2", "prefixlen": 32}]},
            {"ifname": "docker0", "addr_info": [{"local": "172.17.0.1", "prefixlen": 16}]},
        ])
        self.assertEqual([str(i) for i in wakepc.parse_ip_addr(text)], ["192.168.1.10/24"])
        self.assertEqual(wakepc.parse_ip_addr("not json"), [])

    def test_a_sweep_covers_the_subnet_but_not_a_huge_one(self):
        small = wakepc.sweep_targets(wakepc.ipaddress.ip_interface("192.168.1.10/24"))
        self.assertEqual(len(small), 253)
        self.assertNotIn("192.168.1.10", small)
        huge = wakepc.sweep_targets(wakepc.ipaddress.ip_interface("10.4.5.6/8"))
        self.assertEqual((len(huge), huge[0]), (253, "10.4.5.1"))

    def test_a_scan_lists_the_lan_and_marks_known_machines(self):
        store = wakepc.MachineStore(None)
        with mock.patch.object(wakepc, "local_networks", return_value=LAN):
            store.add(wakepc.validate_machine({"name": "Desk PC", "mac": "3C:7C:3F:12:34:56"}))
            neighbours = [
                ("192.168.1.50", "3c7c3f123456"),
                ("192.168.1.2", "3c7c3f000002"),
                ("192.168.1.10", "3c7c3f00000a"),   # the relay itself
                ("172.17.0.5", "3c7c3f000099"),     # not on the home network
            ]
            wakepc._scan_cache = (0.0, None)
            with mock.patch.object(wakepc, "nudge_arp") as nudge, \
                    mock.patch.object(wakepc, "read_neighbours", return_value=neighbours), \
                    mock.patch.object(wakepc, "resolve_names", return_value={"192.168.1.50": "desk"}):
                result = wakepc.scan_lan(store, settle=0)
                wakepc.scan_lan(store, settle=0)  # served from cache: no second sweep
        self.assertEqual(nudge.call_count, 1)
        self.assertEqual(result["networks"], ["192.168.1.0/24"])
        self.assertEqual(result["devices"], [
            {"ip": "192.168.1.2", "mac": "3c:7c:3f:00:00:02", "name": None, "machine": None},
            {"ip": "192.168.1.50", "mac": "3c:7c:3f:12:34:56", "name": "desk", "machine": "desk-pc"},
        ])


class RecoveryTest(unittest.TestCase):
    """The relay once read its tailnet address only at startup. When the
    address was not there yet, or changed later, it listened where nothing
    arrived until the whole host was rebooted."""

    AUTO = {"bind_host": "auto"}

    def test_it_follows_the_tailnet_address(self):
        with mock.patch.object(wakepc, "own_tailnet_addr", return_value="100.64.0.9"):
            self.assertEqual(wakepc.wanted_bind(self.AUTO, "100.64.0.2"), "100.64.0.9")
            self.assertEqual(wakepc.wanted_bind(self.AUTO, "0.0.0.0"), "100.64.0.9")

    def test_it_holds_on_while_tailscale_is_briefly_away(self):
        with mock.patch.object(wakepc, "own_tailnet_addr", return_value=None):
            self.assertEqual(wakepc.wanted_bind(self.AUTO, "100.64.0.2"), "100.64.0.2")
            self.assertEqual(wakepc.wanted_bind(self.AUTO), "0.0.0.0")

    def test_an_explicit_address_is_left_alone(self):
        with mock.patch.object(wakepc, "own_tailnet_addr", return_value="100.64.0.9"):
            self.assertEqual(wakepc.wanted_bind({"bind_host": "10.0.0.5"}, "10.0.0.5"), "10.0.0.5")

    def test_a_changed_address_stops_the_server_so_it_rebinds(self):
        server = mock.Mock()
        with mock.patch.object(wakepc, "REBIND_CHECK_S", 0), mock.patch("builtins.print"), \
                mock.patch.object(wakepc, "own_tailnet_addr", side_effect=["100.64.0.2", None, "100.64.0.9"]):
            wakepc.watch_bind(self.AUTO, server, "100.64.0.2")
        server.shutdown.assert_called_once()

    def test_key_expiry_is_read_from_tailscale(self):
        now = wakepc.datetime.datetime(2026, 11, 17, 12, 0, 0)
        soon = json.dumps({"Self": {"KeyExpiry": "2026-11-20T12:00:00.123456789Z"}})
        self.assertEqual(wakepc.parse_key_expiry(soon, now), 3)
        self.assertIsNone(wakepc.parse_key_expiry(json.dumps({"Self": {}}), now))  # expiry off
        self.assertIsNone(wakepc.parse_key_expiry(b"garbage", now))


class MachinesApiTest(unittest.TestCase):
    """The whole first-wake path as an app drives it, against a real relay on
    loopback with only the network itself (packets, pings) faked."""

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
        wakepc.Handler.store = wakepc.MachineStore(None)

    def setUp(self):
        wakepc.Handler.store = wakepc.MachineStore(None, reserved=wakepc.Handler.config["commands"])
        wakepc.Handler.config["phone_setup"] = True
        for name, value in [("local_networks", LAN), ("key_expiry_days", None), ("WAIT_STEP_S", 0)]:
            patcher = mock.patch.object(wakepc, name, **(
                {"return_value": value} if callable(getattr(wakepc, name)) else {"new": value}))
            patcher.start()
            self.addCleanup(patcher.stop)

    def call(self, method, path, body=None, token="test-token"):
        request = urllib.request.Request(
            self.base + path, method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": f"Bearer {token}"} if token else {},
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read())

    def add(self, **extra):
        return self.call("POST", "/machines",
                         {"name": "Desk PC", "mac": "3C:7C:3F:12:34:56", "ip": "192.168.1.50", **extra})

    def test_health_says_what_an_app_needs_to_know(self):
        code, body = self.call("GET", "/health")
        self.assertEqual(code, 200)
        self.assertEqual((body["ok"], body["api"], body["machines"], body["phone_setup"]),
                         (True, wakepc.API_VERSION, 0, True))
        self.assertIsInstance(body["warnings"], list)

    def test_health_warns_before_the_tailscale_key_lapses(self):
        with mock.patch.object(wakepc, "key_expiry_days", return_value=5):
            body = self.call("GET", "/health")[1]
        self.assertTrue(any("expires in 5 days" in w for w in body["warnings"]))

    def test_everything_needs_the_caller_to_be_recognised(self):
        for method, path in [("GET", "/health"), ("GET", "/scan"), ("GET", "/machines"),
                             ("POST", "/machines"), ("PUT", "/machines/x"), ("DELETE", "/machines/x"),
                             ("POST", "/machines/x/wake"), ("GET", "/machines/x/status")]:
            with self.subTest(path=path, method=method):
                self.assertEqual(self.call(method, path, token=None)[0], 401)

    def test_add_list_change_and_forget_a_machine(self):
        code, machine = self.add(ready=445)
        self.assertEqual(code, 201)
        self.assertEqual(machine, {
            "id": "desk-pc", "name": "Desk PC", "mac": "3c:7c:3f:12:34:56",
            "ip": "192.168.1.50", "ready": 445, "broadcast_ip": "192.168.1.255",
        })
        self.assertEqual(self.call("GET", "/machines")[1], {"machines": [machine]})

        code, changed = self.call("PUT", "/machines/desk-pc", {"name": "Tower", "ready": None})
        self.assertEqual((code, changed["name"], changed["ready"], changed["mac"]),
                         (200, "Tower", None, "3c:7c:3f:12:34:56"))

        self.assertEqual(self.call("DELETE", "/machines/desk-pc")[0], 200)
        self.assertEqual(self.call("DELETE", "/machines/desk-pc")[0], 404)
        self.assertEqual(self.call("GET", "/machines")[1], {"machines": []})

    def test_auto_ready_picks_a_port_the_machine_answers_on(self):
        # Added while it is on, so the relay can see what it offers: here
        # remote desktop is shut and file sharing answers.
        with mock.patch.object(wakepc, "probe_port", side_effect=lambda ip, port, timeout=1: port == 445) as probe:
            code, machine = self.add(ready="auto")
        self.assertEqual((code, machine["ready"]), (201, 445))
        self.assertEqual([c.args[:2] for c in probe.call_args_list],
                         [("192.168.1.50", 3389), ("192.168.1.50", 445)])

    def test_auto_ready_with_nothing_answering_means_no_ready_port(self):
        with mock.patch.object(wakepc, "probe_port", return_value=False):
            self.assertIsNone(self.add(ready="auto")[1]["ready"])
        # No address, nothing to look at, and nothing is probed.
        with mock.patch.object(wakepc, "probe_port") as probe:
            code, machine = self.call("POST", "/machines",
                                      {"name": "Other", "mac": "3C:7C:3F:12:34:99", "ready": "auto"})
        self.assertEqual((code, machine["ready"]), (201, None))
        probe.assert_not_called()

    def test_auto_ready_never_probes_an_address_off_the_lan(self):
        with mock.patch.object(wakepc, "probe_port") as probe:
            self.assertEqual(self.add(ip="8.8.8.8", ready="auto")[0], 400)
        probe.assert_not_called()

    def test_a_bad_machine_is_refused_with_the_reason(self):
        code, body = self.add(mac="AA:BB:CC:DD:EE:FF")
        self.assertEqual(code, 400)
        self.assertIn("example MAC", body["error"])
        self.assertEqual(self.add(ip="8.8.8.8")[0], 400)
        self.assertEqual(self.call("PUT", "/machines/nope", {"name": "x"})[0], 404)

    def test_phone_setup_can_be_switched_off(self):
        self.add()
        wakepc.Handler.config["phone_setup"] = False
        self.assertEqual(self.add(mac="3C:7C:3F:12:34:57")[0], 403)
        self.assertEqual(self.call("DELETE", "/machines/desk-pc")[0], 403)
        # What is already there can still be listed and woken.
        self.assertEqual(len(self.call("GET", "/machines")[1]["machines"]), 1)

    def test_a_machine_shows_up_as_a_command_for_older_apps(self):
        self.add()
        names = {c["name"]: c for c in self.call("GET", "/commands")[1]["commands"]}
        self.assertEqual(names["wake-desk-pc"], {"name": "wake-desk-pc", "ping": True, "elevated": False})
        with mock.patch.object(wakepc, "send_magic_packet") as send:
            self.assertEqual(self.call("POST", "/run/wake-desk-pc")[0], 200)
        send.assert_called_once_with("3c7c3f123456", "192.168.1.255")

    def test_wake_sends_the_packet_and_returns_at_once(self):
        self.add()
        with mock.patch.object(wakepc, "send_magic_packet") as send, \
                mock.patch.object(wakepc, "probe_ping") as ping:
            code, body = self.call("POST", "/machines/desk-pc/wake")
        self.assertEqual((code, body), (200, {"sent": True}))
        send.assert_called_once_with("3c7c3f123456", "192.168.1.255")
        ping.assert_not_called()
        self.assertEqual(self.call("POST", "/machines/nope/wake")[0], 404)

    def test_wake_and_wait_holds_on_until_the_machine_is_ready(self):
        self.add(ready=445)
        pings = [{"awake": False, "sent": 1}, {"awake": True, "sent": 1}, {"awake": True, "sent": 1}]
        ports = [False, True]
        with mock.patch.object(wakepc, "send_magic_packet"), \
                mock.patch.object(wakepc, "probe_ping", side_effect=pings), \
                mock.patch.object(wakepc, "probe_port", side_effect=ports):
            code, body = self.call("POST", "/machines/desk-pc/wake?wait=60")
        # asleep, then up (pings, port shut), then ready.
        self.assertEqual((code, body["sent"], body["state"], body["ready"]), (200, True, "ready", True))
        self.assertIs(body["sent"], True)  # not the ping's packet count

    def test_wake_and_wait_gives_up_and_says_how_far_it_got(self):
        self.add()
        with mock.patch.object(wakepc, "send_magic_packet"), \
                mock.patch.object(wakepc, "probe_ping", return_value={"awake": False}), \
                mock.patch.object(wakepc.time, "monotonic", side_effect=[0, 1, 1, 99, 99, 99]):
            code, body = self.call("POST", "/machines/desk-pc/wake?wait=30")
        self.assertEqual((code, body["state"], body["awake"]), (200, "asleep", False))

    def test_status_names_the_state(self):
        self.add(ready=445)
        with mock.patch.object(wakepc, "probe_ping", return_value={"awake": True, "avg_ms": 0.4}), \
                mock.patch.object(wakepc, "probe_port", return_value=False):
            code, body = self.call("GET", "/machines/desk-pc/status")
        self.assertEqual((code, body["state"], body["awake"], body["ready"]), (200, "up", True, False))

    def test_a_strange_ready_value_is_refused_not_fatal(self):
        # "²" passes str.isdigit and then int() rejects it: that once killed the handler.
        for bad in ("²", "٣", True, 4.5, 0, {"a": 1}):
            with self.subTest(bad):
                self.assertEqual(self.add(ready=bad)[0], 400)

    def test_wake_packets_only_go_to_the_home_network(self):
        self.assertEqual(self.add(broadcast_ip="8.8.8.8")[0], 400)
        self.assertEqual(self.add(broadcast_ip="224.0.0.1")[0], 400)
        self.assertEqual(self.add(broadcast_ip="255.255.255.255")[0], 201)

    def test_addresses_that_are_private_in_name_only_are_refused(self):
        for bad in ("0.0.0.1", "240.0.0.1", "255.255.255.255", "192.0.2.5", "198.18.0.1"):
            with self.subTest(bad):
                self.assertEqual(self.add(ip=bad)[0], 400)

    def test_dropping_the_address_drops_its_port_check(self):
        self.add(ready=445)
        code, changed = self.call("PUT", "/machines/desk-pc", {"ip": None})
        self.assertEqual((code, changed["ip"], changed["ready"]), (200, None, None))

    def test_resending_the_same_address_keeps_a_chosen_broadcast(self):
        self.add(broadcast_ip="192.168.1.127")
        changed = self.call("PUT", "/machines/desk-pc", {"ip": "192.168.1.50", "name": "Tower"})[1]
        self.assertEqual(changed["broadcast_ip"], "192.168.1.127")
        moved = self.call("PUT", "/machines/desk-pc", {"ip": "192.168.1.60"})[1]
        self.assertEqual(moved["broadcast_ip"], "192.168.1.255")

    def test_a_change_to_a_machine_removed_meanwhile_does_not_bring_it_back(self):
        self.add()
        store = wakepc.Handler.store
        fields = wakepc.validate_machine({"name": "Tower"}, base=store.get("desk-pc"))
        store.remove("desk-pc")
        self.assertIsNone(store.update("desk-pc", fields))
        self.assertEqual(store.all(), [])

    def test_registering_a_machine_does_not_move_the_legacy_wake(self):
        self.add(name="Aardvark", mac="3C:7C:3F:12:34:99")
        with mock.patch.object(wakepc, "send_magic_packet") as send:
            self.assertEqual(self.call("POST", "/wake")[0], 200)
        # Still the config's wake-pc, not the newly registered machine.
        send.assert_called_once_with("3c7c3f123456", "192.168.1.255")

    def test_a_page_on_another_site_cannot_post_through_a_browser(self):
        def post(path, origin):
            request = urllib.request.Request(
                self.base + path, method="POST", data=b"{}",
                headers={"Authorization": "Bearer test-token", "Origin": origin},
            )
            try:
                with urllib.request.urlopen(request, timeout=5) as response:
                    return response.status
            except urllib.error.HTTPError as error:
                with error:
                    return error.code

        self.add()
        with mock.patch.object(wakepc, "send_magic_packet") as send, mock.patch("builtins.print"):
            self.assertEqual(post("/machines/desk-pc/wake", "https://evil.example"), 403)
            self.assertEqual(post("/run/wake-pc", "https://evil.example"), 403)
            send.assert_not_called()
            # The relay's own panel is the same origin, and is let through.
            self.assertEqual(post("/machines/desk-pc/wake", self.base), 200)

    def test_scan_is_served(self):
        with mock.patch.object(wakepc, "scan_lan", return_value={"networks": [], "devices": []}):
            self.assertEqual(self.call("GET", "/scan"), (200, {"networks": [], "devices": []}))


if __name__ == "__main__":
    unittest.main()
