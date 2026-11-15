import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from extract_switch_footprints import POWER_SWITCH  # noqa: E402


class PowerSwitchNames(unittest.TestCase):
    def test_urchins_slider_switch_is_a_power_switch(self):
        # duckyb/urchin's BOM: "2x Power switch (MSK 12C02)", placed as this footprint.
        self.assertTrue(POWER_SWITCH.search("urchin-footprints:smt-slider-switch"))

    def test_the_names_read_on_2026_10_04_still_match(self):
        for name in ("Switch_MSK-12C01-07", "switch-MSK-12C02-smd", "switch_SSSS811101",
                     "Buttons_Switches:SW_SPDT+H-MSK12C01_hor(7pin_SMD)"):
            self.assertTrue(POWER_SWITCH.search(name), name)

    def test_a_key_switch_is_not_a_power_switch(self):
        for name in ("Kailh_socket_PG1350_reversible", "SW_Hotswap_Kailh_Choc_V1_tweaked"):
            self.assertFalse(POWER_SWITCH.search(name), name)


if __name__ == "__main__":
    unittest.main()
