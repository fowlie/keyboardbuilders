import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from switch_pins import pin_families  # noqa: E402


def fp(*pads, at="(at 100 50)"):
    return f'(module lib:key_switch (layer F.Cu) {at}\n' + "\n".join(pads) + "\n)"


def hole(x, y, d):
    return f'  (pad "" np_thru_hole circle (at {x} {y}) (size {d} {d}) (drill {d}) (layers *.Cu))'


def pin(n, x, y, d=1.6):
    return f'  (pad {n} thru_hole circle (at {x} {y}) (size 2.6 2.6) (drill {d}) (layers *.Cu))'


def smd(n, x, y):
    return f'  (pad {n} smd rect (at {x} {y}) (size 2.5 2.5) (layers B.Cu))'


CHOC_POSTS = (hole(0, 0, 3.4), hole(-5.5, 0, 1.8), hole(5.5, 0, 1.8))


class PinFamilies(unittest.TestCase):
    def test_ergo42s_hybrid_reads_mx_and_choc_v1(self):
        # Biacco42/Ergo42, ergo42/library.pretty/key_switch.kicad_mod, read 2026-11-16.
        block = fp(hole(-5.5, 0, 1.9), hole(5.5, 0, 1.9),
                   pin(2, 5, 3.8), pin(2, -5, 3.8), pin(1, 0, 5.9),
                   '  (pad 1 thru_hole oval (at -3.175 -3.81 334) (size 2 4) (drill oval 1 3) (layers *.Cu))',
                   pin(2, 3.81, -2.54), pin(2, 2.54, -5.08), pin(1, -2.54, -5.08),
                   hole(0, 0, 3.9878), hole(-5.08, 0, 1.7018), hole(5.08, 0, 1.7018),
                   pin(1, -3.81, -2.54))
        self.assertEqual(pin_families(block), {"MX", "ChocV1"})

    def test_plain_mx(self):
        self.assertEqual(pin_families(fp(hole(0, 0, 4), pin(1, -3.81, -2.54), pin(2, 2.54, -5.08))),
                         {"MX"})

    def test_choc_v1_turned_and_mirrored_is_still_choc_v1(self):
        self.assertEqual(pin_families(fp(*CHOC_POSTS, pin(1, 0, -5.9), pin(2, 5, -3.8))),
                         {"ChocV1"})
        self.assertEqual(pin_families(fp(*CHOC_POSTS, pin(1, 0, 5.9), pin(2, 5, 3.8))),
                         {"ChocV1"})

    def test_choc_pins_without_side_posts_are_not_choc(self):
        # A hybrid with many pins can land one pair on Choc's spacing by chance. Every Choc
        # footprint read in the corpus also drills the two side posts, 11 mm apart.
        self.assertEqual(pin_families(fp(hole(0, 0, 3.4), pin(1, 0, -5.9), pin(2, 5, -3.8))),
                         set())

    def test_choc_pins_round_a_5mm_post_claim_nothing(self):
        # cyril279/keyboards' `keebLib:pg1353`, read 2026-11-16: Choc v2 keeps v1's pins and
        # widens the centre post to 5 mm. A v1 fits that hole too, so it could be either.
        self.assertEqual(pin_families(fp(hole(-5.5, 0, 1.8), hole(5.5, 0, 1.8), hole(0, 0, 4.9),
                                         pin(1, 0, 5.9), pin(2, -5, 3.8))),
                         set())

    def test_kailh_mx_socket_holes_with_surface_pads(self):
        block = fp(hole(0, 0, 4), hole(-3.81, -2.54, 3), hole(2.54, -5.08, 3),
                   smd(1, -7.085, -2.54), smd(2, 5.842, -5.08))
        self.assertEqual(pin_families(block), {"MX"})

    def test_socket_holes_without_pads_are_not_a_switch(self):
        self.assertEqual(pin_families(fp(hole(0, 0, 4), hole(-3.81, -2.54, 3), hole(2.54, -5.08, 3))),
                         set())

    def test_two_pads_on_one_net_are_not_two_pins(self):
        # [[pads-are-not-pins]]: a switch's two pins carry two different signals.
        self.assertEqual(pin_families(fp(hole(0, 0, 4), pin(1, -3.81, -2.54), pin(1, 2.54, -5.08))),
                         set())

    def test_mx_pins_need_an_mx_post(self):
        self.assertEqual(pin_families(fp(hole(0, 0, 5), pin(1, -3.81, -2.54), pin(2, 2.54, -5.08))),
                         set())

    def test_no_centre_hole_claims_nothing(self):
        self.assertEqual(pin_families(fp(pin(1, -3.81, -2.54), pin(2, 2.54, -5.08))), set())


if __name__ == "__main__":
    unittest.main()
