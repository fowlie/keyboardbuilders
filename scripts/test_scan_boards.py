import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from scan_boards import switch_families  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
