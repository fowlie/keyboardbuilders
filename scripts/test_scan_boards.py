import pathlib
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import scan_boards  # noqa: E402
from scan_boards import outline_and_tracks, switch_families  # noqa: E402
from test_switch_pins import fp, hole, pin  # noqa: E402

MX_PINS = (hole(0, 0, 4), pin(1, -3.81, -2.54), pin(2, 2.54, -5.08))


def board(*footprints):
    return "(kicad_pcb\n" + "\n".join(footprints) + "\n)"


def named(name, *pads):
    return fp(*pads).replace("lib:key_switch", name, 1)


class SwitchFamilies(unittest.TestCase):
    def test_a_name_with_no_family_takes_its_pins(self):
        text = board(named("library:key_switch", *MX_PINS))
        self.assertEqual(switch_families(text, ["library:key_switch"]),
                         ({"MX"}, {"library:key_switch": ["MX"]}))

    def test_a_name_that_says_a_family_is_never_read_by_its_pins(self):
        # The pins would say MX; the name says Choc, and the name wins untouched.
        text = board(named("kbd:choc_hotswap", *MX_PINS))
        self.assertEqual(switch_families(text, ["kbd:choc_hotswap"]), ({"ChocV1"}, {}))

    def test_pins_that_fit_nothing_record_nothing(self):
        text = board(named("library:key_switch", hole(0, 0, 4)))
        self.assertEqual(switch_families(text, ["library:key_switch"]), (set(), {}))


# KiCad 5's header, as `ai03-2725/orbit`'s HEAD has it: the layer TABLE names Edge.Cuts on
# every board, drawn on or not, so the table must not count as an outline.
HEADER = """(kicad_pcb (version 20171130) (host pcbnew 5.1.6)
  (general (thickness 1.6) (drawings 0) (tracks 0) (zones 0) (modules 60) (nets 80))
  (layers (0 F.Cu signal) (31 B.Cu signal) (44 Edge.Cuts user) (45 Margin user))
  (setup (edge_width 0.15) (segment_width 0.2) (pcb_text_width 0.3))
"""
SEGMENT = "  (segment (start 1 2) (end 3 4) (width 0.25) (layer F.Cu) (net 1))\n"


class OutlineAndTracks(unittest.TestCase):
    """Issue #41: an unplaced netlist import -- no outline, no tracks -- published as Orbit."""

    def test_an_unplaced_netlist_import_has_neither(self):
        text = HEADER + named("MX_Alps_Hybrid:MX-1U", *MX_PINS) + ")"
        self.assertEqual(outline_and_tracks(text), (False, 0))

    def test_a_drawn_outline_counts_in_either_spelling(self):
        for line in ("(gr_line (start 0 0) (end 9 0) (layer Edge.Cuts) (width 0.15))",
                     '(gr_rect (start 0 0) (end 9 9) (layer "Edge.Cuts") (width 0.1))'):
            self.assertEqual(outline_and_tracks(HEADER + line + ")"), (True, 0), line)

    def test_an_outline_drawn_inside_a_footprint_counts(self):
        text = HEADER + '(footprint "kbd:outline" (fp_line (start 0 0) (end 9 0) (layer "Edge.Cuts")))' + ")"
        self.assertEqual(outline_and_tracks(text)[0], True)

    def test_tracks_are_counted(self):
        self.assertEqual(outline_and_tracks(HEADER + SEGMENT * 3 + ")"), (False, 3))


    def test_a_file_over_the_cap_is_judged_from_its_whole_reread(self):
        text = HEADER + SEGMENT + ")"
        reads = [(text, None, scan_boards.CAP), (text, None, scan_boards.CAP + 5)]
        with mock.patch.object(scan_boards, "fetch", side_effect=lambda *a, **k: reads.pop(0)):
            got = scan_boards.classify("a/b", "b.kicad_pcb")
        self.assertEqual((got["outline"], got["tracks"]), (False, 1))

    def test_a_file_read_only_in_part_records_neither(self):
        text = HEADER + ")"
        reads = [(text, None, scan_boards.CAP), (None, "TimeoutError", 0)]
        with mock.patch.object(scan_boards, "fetch", side_effect=lambda *a, **k: reads.pop(0)):
            got = scan_boards.classify("a/b", "b.kicad_pcb")
        self.assertNotIn("outline", got)


if __name__ == "__main__":
    unittest.main()
