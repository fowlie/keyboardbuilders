"""Tests for build_parts.check -- what seed/parts.toml may say before the site reads it."""
import copy
import pathlib
import sys
import tomllib
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_parts import REPO, check, to_json  # noqa: E402

SEED = {
    "shop": [{"slug": "42keebs", "name": "42. Keebs", "currency": "EUR",
              "prices_include_vat": False, "vat": {"NO": 25}}],
    "part": [
        {"slug": "kailh-mx-hotswap-socket", "name": "Kailh MX hotswap socket",
         "fits": {"switch_socket": ["mx_socket"]}},
        {"slug": "1n4148w-sod123f", "name": "1N4148W", "fits": {"diode": "sod_123f"}},
        {"slug": "nice-nano-v2", "dev_board": "nice-nano-v2"},
        {"slug": "gateron-pro-red-milky", "name": "Gateron Pro Red Milky", "fits": {"switch": ["mx"]},
         "switch_kind": "linear"},
    ],
    "listing": [
        {"shop": "42keebs", "ref": "855", "product": "kailh-mx-hotswap-socket", "pack": 10,
         "url": "https://42keebs.eu/855"},
        {"shop": "42keebs", "ref": "6241", "product": "42keebs-lily58-pro", "pack": 1,
         "url": "https://42keebs.eu/6241"},
    ],
    "diode_package": [{"design": "lily58-pro", "package": "sod_123", "source": "board-scan.json"}],
    "kit": [{
        "slug": "42keebs-lily58-pro", "name": "Lily58 Pro kit", "shop": "42keebs",
        "url": "https://42keebs.eu/kit", "based_on": "lily58-pro", "read_on": "2026-10-04",
        "contents": [{"quantity": 60, "text": "SMD diodes", "kind": "diodes"},
                     {"text": "Brass standoffs and screws"}],
        "needs": [{"fits": {"controller_socket": ["pro_micro"]}, "quantity": 2}],
        "not_priced": ["TRRS cable"],
    }],
}
BOARDS = {"nice-nano-v2", "pro-micro"}
DESIGNS = {"lily58-pro"}


def changed(edit):
    seed = copy.deepcopy(SEED)
    edit(seed)
    return seed


class Check(unittest.TestCase):

    def refused(self, seed, phrase):
        with self.assertRaises(SystemExit) as caught:
            check(seed, BOARDS, DESIGNS)
        self.assertIn(phrase, str(caught.exception))

    def test_a_good_seed_passes(self):
        check(SEED, BOARDS, DESIGNS)

    def test_the_real_seed_passes(self):
        seed = tomllib.loads((REPO / "seed/parts.toml").read_text())
        boards = {b["slug"] for b in tomllib.loads((REPO / "seed/controllers.toml").read_text())["entry"]}
        check(seed, boards, {"lily58-pro"})

    def test_a_pack_of_none_is_refused(self):
        self.refused(changed(lambda s: s["listing"][0].update(pack=0)), "pack")

    def test_two_listings_with_one_ref_are_refused(self):
        self.refused(changed(lambda s: s["listing"][1].update(ref="855")), "855")

    def test_a_listing_of_a_product_nobody_defined_is_refused(self):
        self.refused(changed(lambda s: s["listing"][0].update(product="gateron-socket")), "gateron-socket")

    def test_a_dev_board_missing_from_the_controller_catalogue_is_refused(self):
        self.refused(changed(lambda s: s["part"][2].update(dev_board="frood")), "frood")

    def test_a_dev_board_does_not_restate_its_name(self):
        self.refused(changed(lambda s: s["part"][2].update(name="nice!nano")), "nice-nano-v2")

    def test_a_kit_based_on_no_design_is_refused(self):
        self.refused(changed(lambda s: s["kit"][0].update(based_on="corne-v3")), "corne-v3")

    def test_a_diode_package_for_no_design_is_refused(self):
        self.refused(changed(lambda s: s["diode_package"][0].update(design="corne-v3")), "corne-v3")

    def test_an_unknown_token_is_refused(self):
        self.refused(changed(lambda s: s["part"][1].update(fits={"diode": "sod123"})), "sod123")
        self.refused(changed(lambda s: s["part"][0].update(fits={"switch_socket": ["solder"]})), "solder")
        self.refused(changed(lambda s: s["kit"][0]["needs"][0].update(fits={"controller_socket": ["promicro"]})),
                     "promicro")

    def test_a_fitting_names_one_interface(self):
        self.refused(changed(lambda s: s["part"][0]["fits"].update(diode="sod_123")), "one of")

    def test_a_kit_line_may_be_the_pcb(self):
        check(changed(lambda s: s["kit"][0]["contents"].append({"quantity": 2, "text": "main PCBs", "kind": "pcb"})),
              BOARDS, DESIGNS)

    def test_the_real_kits_main_pcbs_are_the_pcb(self):
        seed = tomllib.loads((REPO / "seed/parts.toml").read_text())
        lily = next(k for k in seed["kit"] if k["slug"] == "42keebs-lily58-pro")
        self.assertEqual("pcb", next(c for c in lily["contents"] if c["text"] == "main PCBs").get("kind"))

    # "own" is the build list's "I have this" in the URL (#113), so no product may be called it.
    def test_a_product_slug_of_own_is_refused(self):
        self.refused(changed(lambda s: s["part"][1].update(slug="own")), "own")

    # Switches (#115): they fit a design's switch footprint, in the design seed's own tokens.
    def test_a_switch_names_footprints_the_design_seed_knows(self):
        check(changed(lambda s: s["part"][3]["fits"].update(switch=["choc_v1", "choc_v2"])), BOARDS, DESIGNS)
        self.refused(changed(lambda s: s["part"][3]["fits"].update(switch=["cherry_mx"])), "cherry_mx")
        self.refused(changed(lambda s: s["part"][3]["fits"].update(switch=[])), "switch")
        # Bespoke is one design's own switch, never a part on a shelf.
        self.refused(changed(lambda s: s["part"][3]["fits"].update(switch=["bespoke"])), "bespoke")

    # The kind is the vendor's word, or left out: never guessed from a colour.
    def test_a_switch_kind_is_one_of_five_or_left_out(self):
        for kind in ("linear", "tactile", "silent_linear", "silent_tactile", "clicky"):
            check(changed(lambda s: s["part"][3].update(switch_kind=kind)), BOARDS, DESIGNS)
        check(changed(lambda s: s["part"][3].pop("switch_kind")), BOARDS, DESIGNS)
        self.refused(changed(lambda s: s["part"][3].update(switch_kind="silent")), "silent")

    def test_only_a_switch_has_a_switch_kind(self):
        self.refused(changed(lambda s: s["part"][0].update(switch_kind="linear")), "kailh-mx-hotswap-socket")

    def test_a_kit_may_hold_or_need_switches(self):
        check(changed(lambda s: s["kit"][0]["contents"].append({"quantity": 58, "text": "switches", "kind": "switches"})),
              BOARDS, DESIGNS)
        check(changed(lambda s: s["kit"][0]["needs"].append({"fits": {"switch": ["mx", "choc_v1"]}, "quantity": 58})),
              BOARDS, DESIGNS)

    def test_the_real_seed_sells_switches_with_a_pack_each(self):
        seed = tomllib.loads((REPO / "seed/parts.toml").read_text())
        switches = {p["slug"] for p in seed["part"] if "switch" in p.get("fits", {})}
        self.assertGreaterEqual(len(switches), 17)
        listed = {row["product"] for row in seed["listing"]}
        self.assertEqual(set(), switches - listed, "a switch nobody lists")

    # A switch's spec (#115) is what the vendor's listing states, per that shop and dated.
    SPEC = {"per": "42keebs", "read_on": "2026-10-05", "actuation_force_gf": 45, "bottom_out_force_gf": 63.5,
            "actuation_mm": 2, "travel_mm": 4, "factory_lubed": True, "pins": 5,
            "housing": "Black nylon top and bottom", "stem": "Black POM", "vendor_says": "Very smooth"}

    def with_spec(self, **edit):
        spec = dict(self.SPEC, **edit)
        return changed(lambda s: s["part"][3].update(spec={k: v for k, v in spec.items() if v is not None}))

    def test_a_switch_spec_states_facts_in_fixed_units(self):
        check(self.with_spec(), BOARDS, DESIGNS)
        check(self.with_spec(actuation_force_gf=None, factory_lubed=None, housing=None), BOARDS, DESIGNS)

    def test_a_spec_says_whose_listing_and_when(self):
        self.refused(self.with_spec(per=None), "per")
        self.refused(self.with_spec(per="kbdfans"), "kbdfans")
        self.refused(self.with_spec(read_on=None), "read_on")

    def test_a_spec_refuses_a_number_out_of_range_or_a_word_for_a_number(self):
        self.refused(self.with_spec(actuation_force_gf=0), "actuation_force_gf")
        self.refused(self.with_spec(bottom_out_force_gf="60 gf"), "bottom_out_force_gf")
        self.refused(self.with_spec(travel_mm=40), "travel_mm")
        self.refused(self.with_spec(pins=4), "pins")
        self.refused(self.with_spec(factory_lubed="yes"), "factory_lubed")
        self.refused(self.with_spec(stem=""), "stem")

    def test_a_spec_key_nobody_knows_is_refused(self):
        self.refused(self.with_spec(force=50), "force")

    def test_only_a_switch_has_a_spec(self):
        self.refused(changed(lambda s: s["part"][0].update(spec=dict(self.SPEC))), "kailh-mx-hotswap-socket")

    def test_the_real_seed_states_specs_for_its_switches(self):
        seed = tomllib.loads((REPO / "seed/parts.toml").read_text())
        specs = {p["slug"]: p["spec"] for p in seed["part"] if "spec" in p}
        self.assertEqual(50, specs["gateron-cj"]["actuation_force_gf"])
        self.assertIs(True, specs["jwick-black"]["factory_lubed"])
        # The vendor gives the Sunrise's force as 35 g in the text and 40 g in the variation: neither is recorded.
        self.assertNotIn("actuation_force_gf", specs.get("ambients-sunrise", {}))

    def test_the_json_keeps_an_uncounted_kit_line_uncounted(self):
        out = to_json(SEED)
        self.assertNotIn("quantity", out["kits"][0]["contents"][1])
        self.assertEqual("2026-10-04", out["kits"][0]["read_on"])


if __name__ == "__main__":
    unittest.main()
