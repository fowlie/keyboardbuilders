"""Tests for discover_repos -- which GitHub repos become a discovery question (#105)."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from discover_repos import firmware_hints, hardware_files, update  # noqa: E402


def hit(name, pushed="2026-11-01T00:00:00Z", created="2026-10-20T00:00:00Z"):
    return {"full_name": name, "pushed_at": pushed, "created_at": created,
            "default_branch": "main", "description": "a keyboard",
            "stargazers_count": 3, "license": {"spdx_id": "MIT"}, "html_url": "x"}


class HardwareFiles(unittest.TestCase):

    def test_a_kicad_board_is_hardware(self):
        self.assertEqual(hardware_files(["pcb/left.kicad_pcb", "README.md"]),
                         ["pcb/left.kicad_pcb"])

    def test_a_keymap_repo_has_none(self):
        self.assertEqual(hardware_files(["config/corne.keymap", "build.yaml"]), [])


class FirmwareHints(unittest.TestCase):

    def test_each_framework_is_named_once(self):
        paths = ["zephyr/module.yml", "boards/shields/x/x.overlay",
                 "qmk/keyboards/x/keyboard.json", "rmk/keyboard.toml"]
        self.assertEqual(firmware_hints(paths), ["qmk", "rmk", "zmk"])

    def test_nothing_found_is_an_empty_list_not_a_verdict(self):
        self.assertEqual(firmware_hints(["pcb/x.kicad_pcb"]), [])


class Update(unittest.TestCase):

    def setUp(self):
        self.fetched = []

    def scan(self, previous, hits, files, known=frozenset(), since="2026-10-01",
             switches=40):
        def fetch_tree(name, branch):
            self.fetched.append(name)
            return files.get(name, [])
        return update(previous, hits, fetch_tree, lambda name, pcb: switches, known, since)

    def test_a_repo_with_a_pcb_becomes_a_candidate(self):
        out = self.scan({}, [hit("a/kbd")], {"a/kbd": ["x.kicad_pcb"]})
        self.assertEqual(out["candidates"]["a/kbd"]["pcb"], ["x.kicad_pcb"])

    def test_a_repo_the_corpus_has_is_never_fetched(self):
        out = self.scan({}, [hit("Weteor/3W6")], {}, known={"weteor/3w6"})
        self.assertEqual(self.fetched, [])
        self.assertEqual(out["candidates"], {})

    def test_a_rejected_repo_is_not_read_again_until_it_is_pushed(self):
        seen = self.scan({}, [hit("a/app")], {})
        self.scan(seen, [hit("a/app")], {})
        self.assertEqual(self.fetched, ["a/app"])
        self.scan(seen, [hit("a/app", pushed="2026-11-05T00:00:00Z")], {})
        self.assertEqual(self.fetched, ["a/app", "a/app"])

    def test_a_candidate_stays_when_search_stops_returning_it(self):
        seen = self.scan({}, [hit("a/kbd")], {"a/kbd": ["x.kicad_pcb"]})
        out = self.scan(seen, [], {}, since="2026-12-01")
        self.assertIn("a/kbd", out["candidates"])

    def test_rejects_older_than_the_window_are_forgotten(self):
        seen = self.scan({}, [hit("a/app")], {})
        out = self.scan(seen, [], {}, since="2026-12-01")
        self.assertEqual(out["checked"], {})

    def test_a_board_with_few_switches_is_not_a_keyboard(self):
        out = self.scan({}, [hit("a/kbd")], {"a/kbd": ["x.kicad_pcb"]}, switches=6)
        self.assertEqual(out["candidates"], {})
        self.assertIn("a/kbd", out["checked"])

    def test_twelve_switches_is_enough_for_one_half_of_a_small_split(self):
        out = self.scan({}, [hit("a/kbd")], {"a/kbd": ["x.kicad_pcb"]}, switches=12)
        self.assertEqual(out["candidates"]["a/kbd"]["switches"], 12)

    def test_a_macropad_or_midi_project_is_not_asked_about(self):
        for words in ["My Hackpad macropad", "MIDI keypad for Half Life week 3"]:
            h = dict(hit("a/pad"), description=words)
            out = self.scan({}, [h], {"a/pad": ["x.kicad_pcb"]})
            self.assertEqual(out["candidates"], {}, words)

    def test_a_candidate_the_corpus_later_gains_is_dropped(self):
        seen = self.scan({}, [hit("a/kbd")], {"a/kbd": ["x.kicad_pcb"]})
        out = self.scan(seen, [], {}, known={"a/kbd"})
        self.assertEqual(out["candidates"], {})


if __name__ == "__main__":
    unittest.main()
