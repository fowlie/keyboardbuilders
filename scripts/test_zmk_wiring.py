#!/usr/bin/env python3
"""Tests for `zmk_wiring.split_wiring`, the split-shield rule.

The first Python tests in `scripts/`, written with the standard library's `unittest` so
they need nothing installed. Run them from the repo root:

    python3 -m unittest discover -s scripts -p 'test_*.py'

Each fixture is a miniature shield directory written to a temporary folder, shaped after
a real upstream shield (named in each test), so a case is readable without a ZMK
checkout. The last class reads the real checkout too, where there is one, because the
rule's whole point is what it does to those shields; it skips itself where there is not.
"""
import os
import pathlib
import tempfile
import textwrap
import unittest

import zmk_wiring

ROWS_5 = """
        row-gpios
            = <&pro_micro 5 (GPIO_ACTIVE_HIGH | GPIO_PULL_DOWN)>
            , <&pro_micro 6 (GPIO_ACTIVE_HIGH | GPIO_PULL_DOWN)>
            , <&pro_micro 7 (GPIO_ACTIVE_HIGH | GPIO_PULL_DOWN)>
            , <&pro_micro 8 (GPIO_ACTIVE_HIGH | GPIO_PULL_DOWN)>
            , <&pro_micro 9 (GPIO_ACTIVE_HIGH | GPIO_PULL_DOWN)>
            ;
"""


def cols(n):
    pins = [19, 18, 15, 14, 16, 10, 20, 21, 4][:n]
    return ("col-gpios = " + ", ".join(f"<&pro_micro {p} GPIO_ACTIVE_HIGH>" for p in pins)
            + ";")


def dtsi(body, label="kscan0", kind="matrix"):
    return textwrap.dedent(f"""
        #include <dt-bindings/zmk/matrix_transform.h>
        / {{
            chosen {{ zmk,kscan = &{label}; }};
            {label}: kscan {{
                compatible = "zmk,kscan-gpio-{kind}";
                wakeup-source;
                {body}
            }};
        }};
        """)


def half(name, override="", label="kscan0"):
    text = f'#include "{name}.dtsi"\n'
    if override:
        text += f"&{label} {{\n    {override}\n}};\n"
    return text


class SplitWiring(unittest.TestCase):
    def shield(self, name, files):
        root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(root))
        d = root/name
        d.mkdir()
        for fn, text in files.items():
            (d/fn).write_text(text)
        return d

    def test_rows_in_the_dtsi_and_columns_in_each_half_read_as_one_half(self):
        # lily58: rows and diode direction in lily58.dtsi, six columns per half.
        d = self.shield("lily58", {
            "lily58.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "lily58_left.overlay": half("lily58", cols(6)),
            "lily58_right.overlay": half("lily58", cols(6)),
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("matrix", 5, 6, "COL2ROW"))

    def test_row2col_is_spelled_the_qmk_way(self):
        # splitreus62 states row2col.
        d = self.shield("splitreus62", {
            "splitreus62.dtsi": dtsi('diode-direction = "row2col";' + ROWS_5),
            "splitreus62_left.overlay": half("splitreus62", cols(6)),
            "splitreus62_right.overlay": half("splitreus62", cols(6)),
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("matrix", 5, 6, "ROW2COL"))

    def test_everything_in_the_dtsi_and_bare_halves(self):
        # ergodash: rows, columns and diodes all in ergodash.dtsi; the halves only include it.
        d = self.shield("ergodash", {
            "ergodash.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5 + cols(7)),
            "ergodash_left.overlay": half("ergodash"),
            "ergodash_right.overlay": half("ergodash"),
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("matrix", 5, 7, "COL2ROW"))

    def test_halves_with_their_own_kscan_nodes_that_disagree_are_refused(self):
        # quefrency: each half defines its whole kscan node, 5 x 7 left and 6 x 8 right.
        six_rows = ROWS_5.replace(";", ", <&pro_micro 4 (GPIO_ACTIVE_HIGH)>;", 1)
        node = lambda body: textwrap.dedent(f"""
            #include "quefrency.dtsi"
            / {{
                kscan0: kscan {{
                    compatible = "zmk,kscan-gpio-matrix";
                    diode-direction = "col2row";
                    {body}
                }};
            }};
            """)
        d = self.shield("quefrency", {
            "quefrency.dtsi": "/ { chosen { zmk,kscan = &kscan0; }; };\n",
            "quefrency_left.overlay": node(cols(7) + ROWS_5),
            "quefrency_right.overlay": node(cols(8) + six_rows),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_halves_with_their_own_kscan_nodes_that_agree_are_read(self):
        node = textwrap.dedent(f"""
            #include "twin.dtsi"
            / {{
                kscan0: kscan {{
                    compatible = "zmk,kscan-gpio-matrix";
                    diode-direction = "col2row";
                    {cols(6)}
                    {ROWS_5}
                }};
            }};
            """)
        d = self.shield("twin", {
            "twin.dtsi": "/ { chosen { zmk,kscan = &kscan0; }; };\n",
            "twin_left.overlay": node,
            "twin_right.overlay": node,
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("matrix", 5, 6, "COL2ROW"))

    def test_halves_with_different_column_counts_are_refused(self):
        # fourier: six columns on the left, seven on the right.
        d = self.shield("fourier", {
            "fourier.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "fourier_left.overlay": half("fourier", cols(6)),
            "fourier_right.overlay": half("fourier", cols(7)),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_halves_with_different_diode_directions_are_refused(self):
        d = self.shield("odd", {
            "odd.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "odd_left.overlay": half("odd", cols(6)),
            "odd_right.overlay": half("odd", cols(6) + '\n diode-direction = "row2col";'),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_an_unstated_diode_direction_is_refused_not_defaulted(self):
        d = self.shield("nodd", {
            "nodd.dtsi": dtsi(ROWS_5),
            "nodd_left.overlay": half("nodd", cols(6)),
            "nodd_right.overlay": half("nodd", cols(6)),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_a_half_with_no_columns_is_refused(self):
        d = self.shield("lopsided", {
            "lopsided.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "lopsided_left.overlay": half("lopsided", cols(6)),
            "lopsided_right.overlay": half("lopsided"),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_a_deleted_property_is_refused(self):
        # Devicetree can take a property away again; this reader does not model that.
        d = self.shield("deleter", {
            "deleter.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "deleter_left.overlay": half("deleter", cols(6)),
            "deleter_right.overlay": half("deleter", cols(6) + "\n /delete-property/ row-gpios;"),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_direct_pins_read_when_both_halves_share_one_node(self):
        # cradio (Sweep): one direct-pin node in cradio.dtsi, 17 inputs, bare halves.
        inputs = "input-gpios = " + ", ".join(
            f"<&pro_micro {p} (GPIO_ACTIVE_LOW | GPIO_PULL_UP)>" for p in range(17)) + ";"
        d = self.shield("cradio", {
            "cradio.dtsi": dtsi(inputs, kind="direct"),
            "cradio_left.overlay": half("cradio"),
            "cradio_right.overlay": half("cradio") + "&default_transform { col-offset = <17>; };\n",
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("direct", 17))

    def test_a_scan_kind_other_than_matrix_or_direct_is_refused(self):
        d = self.shield("charlie", {
            "charlie.dtsi": dtsi("gpios = <&pro_micro 1 0>, <&pro_micro 2 0>;", kind="charlieplex"),
            "charlie_left.overlay": half("charlie"),
            "charlie_right.overlay": half("charlie"),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_two_kscan_nodes_are_refused(self):
        # A composite kscan wraps two others; which one is "the" scan is not this reader's call.
        body = 'diode-direction = "col2row";' + ROWS_5 + cols(6)
        d = self.shield("twoscan", {
            "twoscan.dtsi": dtsi(body) + dtsi(body, label="kscan1"),
            "twoscan_left.overlay": half("twoscan"),
            "twoscan_right.overlay": half("twoscan"),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))

    def test_a_unibody_shield_reads_nothing_here(self):
        # reviung34: one .overlay, no halves. `wiring` answers for it, this does not.
        d = self.shield("reviung34", {
            "reviung34.overlay": dtsi('diode-direction = "col2row";' + ROWS_5 + cols(6)),
        })
        self.assertIsNone(zmk_wiring.split_wiring(d))
        self.assertEqual(zmk_wiring.wiring(d), ("matrix", 5, 6, "COL2ROW"))

    def test_a_split_shield_still_reads_nothing_through_the_unibody_reader(self):
        d = self.shield("lily58", {
            "lily58.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "lily58_left.overlay": half("lily58", cols(6)),
            "lily58_right.overlay": half("lily58", cols(6)),
        })
        self.assertIsNone(zmk_wiring.wiring(d))

    def test_commented_out_pins_are_not_counted(self):
        d = self.shield("commented", {
            "commented.dtsi": dtsi('diode-direction = "col2row";' + ROWS_5),
            "commented_left.overlay": half("commented", cols(6) + "\n// , <&pro_micro 3 0>"),
            "commented_right.overlay": half("commented", "/* " + cols(7) + " */ " + cols(6)),
        })
        self.assertEqual(zmk_wiring.split_wiring(d), ("matrix", 5, 6, "COL2ROW"))


ZMK = pathlib.Path(os.environ.get("ZMK_DIR", os.path.expanduser("~/code/zmk")))/"app/boards/shields"


@unittest.skipUnless(ZMK.is_dir(), f"no ZMK checkout at {ZMK}")
class UpstreamShields(unittest.TestCase):
    """The real shields the rule was written for. Upstream can move under these; a
    failure here is a reason to re-read the shield, not to loosen the rule."""

    def test_quefrency_halves_disagree_and_are_refused(self):
        self.assertIsNone(zmk_wiring.split_wiring(ZMK/"quefrency"))

    def test_fourier_halves_disagree_and_are_refused(self):
        self.assertIsNone(zmk_wiring.split_wiring(ZMK/"fourier"))

    def test_lily58_reads_one_half(self):
        self.assertEqual(zmk_wiring.split_wiring(ZMK/"lily58"), ("matrix", 5, 6, "COL2ROW"))

    def test_splitreus62_reads_row2col(self):
        self.assertEqual(zmk_wiring.split_wiring(ZMK/"splitreus62"), ("matrix", 6, 6, "ROW2COL"))


if __name__ == "__main__":
    unittest.main()
