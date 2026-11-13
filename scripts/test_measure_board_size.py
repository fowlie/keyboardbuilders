"""Tests for measure_board_size -- a board's outline extent and copper layer count.

Run from the repo root:

    python3 -m unittest discover -s scripts -p 'test_*.py'
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from measure_board_size import (board_of, combine, measure, plate_row,  # noqa: E402
                                plates_by_slug, to_toml)

LAYERS_2 = '(layers (0 "F.Cu" signal) (31 "B.Cu" signal) (44 "Edge.Cuts" user))'
LAYERS_5 = "(layers (0 F.Cu signal) (31 B.Cu signal) (44 Edge.Cuts user))"


def v6(*items, layers=LAYERS_2):
    return f'(kicad_pcb (version 20221018) (generator pcbnew) {layers} {" ".join(items)})'


def v5(*items, layers=LAYERS_5):
    return f'(kicad_pcb (version 20171130) (host pcbnew 5.1.9) {layers} {" ".join(items)})'


def line(x1, y1, x2, y2, layer='"Edge.Cuts"', head="gr_line"):
    return f"({head} (start {x1} {y1}) (end {x2} {y2}) (stroke (width 0.1)) (layer {layer}))"


def box(x1, y1, x2, y2, layer='"Edge.Cuts"'):
    return " ".join([line(x1, y1, x2, y1, layer), line(x2, y1, x2, y2, layer),
                     line(x2, y2, x1, y2, layer), line(x1, y2, x1, y1, layer)])


class Extent(unittest.TestCase):
    """The outline's bounding box, in mm, from the Edge.Cuts layer alone."""

    def test_a_drawn_rectangle_is_its_own_size(self):
        got = measure(v6(box(10, 20, 110, 70)))
        self.assertEqual((got["width_mm"], got["height_mm"]), (100.0, 50.0))

    def test_lines_on_other_layers_do_not_count(self):
        # A title block or a silkscreen line far off the board is not the board.
        got = measure(v6(box(0, 0, 100, 50), line(-500, -500, 900, 900, '"F.SilkS"')))
        self.assertEqual((got["width_mm"], got["height_mm"]), (100.0, 50.0))

    def test_a_zero_length_line_cuts_nothing(self):
        # Sofle v1 carries one at (49.7, -38.2), 75 mm above the board. KiCad's own
        # outline ignores it; counting it made the board 185 mm tall instead of 109.
        got = measure(v6(box(0, 0, 100, 50), line(-80, -80, -80, -80)))
        self.assertEqual((got["width_mm"], got["height_mm"], got["pieces"]), (100.0, 50.0, 1))

    def test_a_kicad5_arc_reaches_past_its_endpoints(self):
        # KiCad 5: `start` is the CENTRE, `end` the arc's first point, `angle` the sweep.
        # Centre (100,25), from (100,0), +180 degrees: through (125,25) to (100,50).
        arc = "(gr_arc (start 100 25) (end 100 0) (angle 180) (layer Edge.Cuts) (width 0.1))"
        got = measure(v5(line(0, 0, 100, 0, "Edge.Cuts"), line(0, 50, 100, 50, "Edge.Cuts"),
                         line(0, 0, 0, 50, "Edge.Cuts"), arc))
        self.assertEqual((got["width_mm"], got["height_mm"]), (125.0, 50.0))

    def test_a_kicad6_arc_reaches_past_its_endpoints(self):
        arc = ('(gr_arc (start 100 0) (mid 125 25) (end 100 50) (stroke (width 0.1)) '
               '(layer "Edge.Cuts"))')
        got = measure(v6(line(0, 0, 100, 0), line(0, 50, 100, 50), line(0, 0, 0, 50), arc))
        self.assertEqual((got["width_mm"], got["height_mm"]), (125.0, 50.0))

    def test_a_short_arc_does_not_reach_the_circles_extremes(self):
        # A 90-degree corner from (0,10) round to (10,0) about (10,10): its extent is
        # the square between its ends, not the whole circle.
        arc = '(gr_arc (start 0 10) (mid 2.9289 2.9289) (end 10 0) (layer "Edge.Cuts"))'
        got = measure(v6(arc))
        self.assertAlmostEqual(got["width_mm"], 10.0, places=1)
        self.assertAlmostEqual(got["height_mm"], 10.0, places=1)

    def test_a_circle_is_its_diameter(self):
        got = measure(v6('(gr_circle (center 50 50) (end 60 50) (layer "Edge.Cuts"))'))
        self.assertEqual((got["width_mm"], got["height_mm"]), (20.0, 20.0))

    def test_a_rect_and_a_poly(self):
        got = measure(v6('(gr_rect (start 0 0) (end 30 10) (layer "Edge.Cuts"))',
                         '(gr_poly (pts (xy 0 0) (xy 40 0) (xy 40 -20)) (layer "Edge.Cuts"))'))
        self.assertEqual((got["width_mm"], got["height_mm"]), (40.0, 30.0))

    def test_a_bezier_is_measured_on_the_curve_not_its_handles(self):
        # Handles at y=-40 pull the curve only to y=-30 (3/4 of the way).
        curve = ('(gr_curve (pts (xy 0 0) (xy 0 -40) (xy 100 -40) (xy 100 0)) '
                 '(layer "Edge.Cuts"))')
        got = measure(v6(curve, line(0, 0, 100, 0)))
        self.assertEqual((got["width_mm"], got["height_mm"]), (100.0, 30.0))

    def test_a_footprints_edge_lines_are_placed_and_rotated(self):
        # A footprint at (100,100) turned 90 degrees: its local (10,0) lands at (100,90),
        # KiCad's own rotation, which turns +x towards -y on the screen.
        fp = ('(footprint "Outline" (layer "F.Cu") (at 100 100 90) '
              '(fp_line (start 0 0) (end 10 0) (layer "Edge.Cuts")) '
              '(pad "1" smd rect (at 50 50) (size 1 1) (layers "F.Cu")))')
        got = measure(v6(fp, line(100, 100, 120, 100)))
        self.assertEqual((got["width_mm"], got["height_mm"]), (20.0, 10.0))

    def test_a_kicad5_module_is_read_the_same_way(self):
        fp = ("(module Outline (layer F.Cu) (tedit 0) (at 100 100 90) "
              "(fp_line (start 0 0) (end 10 0) (layer Edge.Cuts) (width 0.1)))")
        got = measure(v5(fp, line(100, 100, 120, 100, "Edge.Cuts")))
        self.assertEqual((got["width_mm"], got["height_mm"]), (20.0, 10.0))

    def test_a_footprint_without_rotation_is_only_moved(self):
        fp = ('(footprint "Hole" (layer "F.Cu") (at 10 10) '
              '(fp_circle (center 0 0) (end 5 0) (layer "Edge.Cuts")))')
        got = measure(v6(fp))
        self.assertEqual((got["width_mm"], got["height_mm"]), (10.0, 10.0))

    def test_no_edge_cuts_is_unknown_not_zero(self):
        got = measure(v6(line(0, 0, 100, 50, '"Dwgs.User"')))
        self.assertNotIn("width_mm", got)
        self.assertIn("Edge.Cuts", got["unknown"])

    def test_an_unparseable_file_is_unknown(self):
        got = measure("this is not a board")
        self.assertNotIn("width_mm", got)
        self.assertIn("unknown", got)


class Layers(unittest.TestCase):
    """Copper layers, from the board's own layer table."""

    def test_two_layers(self):
        self.assertEqual(measure(v6(box(0, 0, 1, 1)))["layers"], 2)

    def test_four_layers_count_the_inner_ones(self):
        table = ('(layers (0 "F.Cu" signal) (1 "In1.Cu" signal) (2 "In2.Cu" power) '
                 '(31 "B.Cu" signal) (44 "Edge.Cuts" user))')
        self.assertEqual(measure(v6(box(0, 0, 1, 1), layers=table))["layers"], 4)

    def test_a_kicad5_table(self):
        self.assertEqual(measure(v5(line(0, 0, 1, 1, "Edge.Cuts")))["layers"], 2)

    def test_a_layer_table_inside_a_footprint_is_not_the_boards(self):
        # Only the top-level `(layers ...)` is the stackup; a pad's `(layers ...)` lists
        # the layers that pad is on.
        got = measure(v6(box(0, 0, 1, 1), layers=""))
        self.assertNotIn("layers", got)


class Pieces(unittest.TestCase):
    """How many separate boards one file's outline draws -- a split's two halves, say."""

    def test_one_outline_with_a_hole_is_one_piece(self):
        got = measure(v6(box(0, 0, 100, 50),
                         '(gr_circle (center 50 25) (end 52 25) (layer "Edge.Cuts"))'))
        self.assertEqual(got["pieces"], 1)

    def test_two_separate_outlines_are_two(self):
        got = measure(v6(box(0, 0, 100, 50), box(150, 0, 250, 50)))
        self.assertEqual(got["pieces"], 2)
        self.assertEqual(got["width_mm"], 250.0)

    def test_each_outlines_own_box_is_kept(self):
        # A split's halves drawn side by side: each half's extent, not just the pair's.
        got = measure(v6(box(0, 0, 100, 50), box(150, 0, 250, 60),
                         '(gr_circle (center 50 25) (end 52 25) (layer "Edge.Cuts"))'))
        self.assertEqual(sorted(got["boxes"]), [[0, 0, 100, 50], [150, 0, 250, 60]])


class Cutouts(unittest.TestCase):
    """Switch-sized holes in an outline: what makes a plate a switch plate."""

    def test_switch_sized_squares_are_counted(self):
        holes = [box(10 + 19.05*i, 10, 24 + 19.05*i, 24) for i in range(4)]
        got = measure(v6(box(0, 0, 100, 40), *holes))
        self.assertEqual(got["switch_cutouts"], 4)
        self.assertEqual(got["pieces"], 1)

    def test_a_rect_cutout_counts_and_a_screw_hole_does_not(self):
        got = measure(v6(box(0, 0, 100, 40),
                         '(gr_rect (start 10 10) (end 24 24) (layer "Edge.Cuts"))',
                         '(gr_circle (center 50 20) (end 51.1 20) (layer "Edge.Cuts"))'))
        self.assertEqual(got["switch_cutouts"], 1)

    def test_a_board_the_size_of_a_cutout_is_not_its_own_cutout(self):
        got = measure(v6(box(0, 0, 14, 14)))
        self.assertEqual(got["switch_cutouts"], 0)


def keys(x0, y0, cols, rows, rot=0.0, pitch=19.05):
    """Switch centres as position-scan.json has them, `[x, y, rotation]`."""
    return [[x0 + pitch*c, y0 + pitch*r, rot] for c in range(cols) for r in range(rows)]


class BoardOf(unittest.TestCase):
    """What one file draws -- a board, one half of a split, or both halves -- and the
    size of each board in it. Checked against the board's own switches."""

    def test_a_unibody_board_is_its_outline(self):
        got = board_of(measure(v6(box(0, 0, 100, 60))), keys(15, 15, 4, 2),
                       split=False, keys=8)
        self.assertEqual(got["drawn"], "board")
        self.assertEqual(got["sizes"], [[100.0, 60.0]])
        self.assertEqual(got["layers"], 2)

    def test_a_split_file_holding_half_the_keys_is_one_half(self):
        got = board_of(measure(v6(box(0, 0, 100, 60))), keys(15, 15, 4, 2),
                       split=True, keys=16)
        self.assertEqual((got["drawn"], got["sizes"]), ("half", [[100.0, 60.0]]))

    def test_both_halves_joined_in_one_outline_give_no_half_size(self):
        # Ferris, Sweep, Corne v4: the halves held by snap-off tabs or a frame are one
        # outline, so the extent is the pair's and no half's size can be read off it.
        tabs = " ".join([line(0, 0, 230, 0), line(230, 0, 230, 60), line(230, 60, 0, 60),
                         line(0, 60, 0, 0)])
        got = board_of(measure(v6(tabs)), keys(15, 15, 4, 2) + keys(145, 15, 4, 2),
                       split=True, keys=16)
        self.assertEqual(got["drawn"], "both halves")
        self.assertNotIn("sizes", got)
        self.assertIn("one outline", got["unknown"])

    def test_both_halves_as_two_outlines_give_each_halfs_size(self):
        got = board_of(measure(v6(box(0, 0, 100, 60), box(130, 0, 232, 61))),
                       keys(15, 15, 4, 2) + keys(145, 15, 4, 2), split=True, keys=16)
        self.assertEqual(got["drawn"], "both halves")
        self.assertEqual(got["sizes"], [[100.0, 60.0], [102.0, 61.0]])
        self.assertNotIn("unknown", got)

    def test_an_outline_beside_the_board_with_no_switches_is_not_the_board(self):
        # A plate or a bottom drawn next to the PCB as its own outline.
        got = board_of(measure(v6(box(0, 0, 100, 60), box(120, 0, 220, 60))),
                       keys(15, 15, 4, 2), split=False, keys=8)
        self.assertEqual(got["sizes"], [[100.0, 60.0]])

    def test_switches_filling_a_fraction_of_the_outline_are_a_panel(self):
        # Soyuz, Vagrant, Framework rev0: plates joined to the PCB in one outline.
        got = board_of(measure(v6(box(0, 0, 300, 60))), keys(15, 15, 4, 2),
                       split=False, keys=8)
        self.assertNotIn("sizes", got)
        self.assertIn("panel", got["unknown"])

    def test_far_more_switches_than_keys_is_several_boards(self):
        # sdra-kb00: two 12-key boards on mousebites, 24 switch footprints.
        got = board_of(measure(v6(box(0, 0, 200, 60))), keys(15, 15, 4, 2) + keys(115, 15, 4, 2),
                       split=False, keys=8)
        self.assertNotIn("sizes", got)
        self.assertIn("16 switches", got["unknown"])

    def test_a_board_drawn_a_quarter_turn_round_is_turned_back(self):
        # Setta21: all 21 switches at 270 degrees, the board drawn on its side.
        got = board_of(measure(v6(box(0, 0, 100, 60))), keys(15, 15, 4, 2, rot=270.0),
                       split=False, keys=8)
        self.assertEqual(got["sizes"], [[60.0, 100.0]])

    def test_a_few_turned_switches_do_not_turn_the_board(self):
        sw = keys(15, 15, 4, 2)
        sw[0][2] = 90.0
        got = board_of(measure(v6(box(0, 0, 100, 60))), sw, split=False, keys=8)
        self.assertEqual(got["sizes"], [[100.0, 60.0]])

    def test_an_outline_away_from_the_switches_is_unknown(self):
        got = board_of(measure(v6(box(0, 0, 100, 60))), keys(415, 15, 4, 2),
                       split=False, keys=8)
        self.assertNotIn("sizes", got)
        self.assertIn("contain", got["unknown"])

    def test_a_split_file_with_no_switches_found_is_unknown(self):
        # Without its switches nothing says whether the file draws one half or both.
        got = board_of(measure(v6(box(0, 0, 100, 60))), None, split=True, keys=16)
        self.assertNotIn("sizes", got)
        self.assertNotIn("drawn", got)

    def test_a_unibody_file_with_no_switches_found_is_its_outline(self):
        got = board_of(measure(v6(box(0, 0, 100, 60))), None, split=False, keys=8)
        self.assertEqual((got["drawn"], got["sizes"]), ("board", [[100.0, 60.0]]))

    def test_an_unmeasured_file_passes_its_reason_on(self):
        got = board_of({"unknown": "could not fetch (HTTPError)", "layers": 2}, None,
                       split=False, keys=8)
        self.assertEqual(got, {"unknown": "could not fetch (HTTPError)", "layers": 2})


class Combine(unittest.TestCase):
    """One design, several board files: a size only where they agree."""

    A = {"drawn": "half", "sizes": [[100.0, 50.0]], "layers": 2}

    def test_one_file_is_its_own_answer(self):
        got = combine({"a.kicad_pcb": self.A})
        self.assertEqual((got["width_mm"], got["height_mm"], got["layers"], got["drawn"]),
                         (100.0, 50.0, 2, "half"))

    def test_two_halves_of_one_size_agree(self):
        got = combine({"l.kicad_pcb": self.A,
                       "r.kicad_pcb": {**self.A, "sizes": [[100.3, 50.0]]}})
        self.assertEqual((got["width_mm"], got["height_mm"]), (100.3, 50.0))
        self.assertNotIn("unknown", got)

    def test_files_of_different_sizes_give_no_size(self):
        got = combine({"v1.kicad_pcb": self.A,
                       "v2.kicad_pcb": {**self.A, "sizes": [[120.0, 50.0]]}})
        self.assertNotIn("width_mm", got)
        self.assertEqual(got["layers"], 2)
        self.assertIn("differ", got["unknown"])

    def test_a_left_and_a_right_of_different_sizes_are_each_halfs(self):
        # Fourier: the right hand is wider. Named by hand, they are the two halves.
        got = combine({"fourier-left/fourier-left.kicad_pcb": self.A,
                       "fourier-right/fourier-right.kicad_pcb":
                           {**self.A, "sizes": [[110.0, 50.0]]}})
        self.assertEqual(got["half_sizes_mm"], [[100.0, 50.0], [110.0, 50.0]])
        self.assertNotIn("width_mm", got)
        self.assertNotIn("unknown", got)

    def test_short_hand_names_count(self):
        got = combine({"Guide68/Assemble_R/Guide68_Assemble_R.kicad_pcb":
                           {**self.A, "sizes": [[169.45, 93.25]]},
                       "Guide68/Assemble_L/Guide68_Assemble_L.kicad_pcb":
                           {**self.A, "sizes": [[131.35, 93.25]]}})
        self.assertEqual(got["half_sizes_mm"], [[131.35, 93.25], [169.45, 93.25]])

    def test_two_left_halves_of_different_sizes_are_variants_not_halves(self):
        got = combine({"BM_L/a_L.kicad_pcb": self.A,
                       "Mid_L/b_L.kicad_pcb": {**self.A, "sizes": [[110.0, 50.0]]}})
        self.assertNotIn("half_sizes_mm", got)
        self.assertIn("differ", got["unknown"])

    def test_a_unibody_left_and_right_are_not_halves(self):
        got = combine({"left.kicad_pcb": {**self.A, "drawn": "board"},
                       "right.kicad_pcb": {**self.A, "drawn": "board",
                                           "sizes": [[110.0, 50.0]]}})
        self.assertIn("differ", got["unknown"])

    def test_both_halves_of_different_sizes_in_one_file(self):
        got = combine({"both.kicad_pcb": {"drawn": "both halves", "layers": 2,
                                          "sizes": [[100.0, 50.0], [110.0, 50.0]]}})
        self.assertEqual(got["half_sizes_mm"], [[100.0, 50.0], [110.0, 50.0]])

    def test_files_drawing_different_things_give_no_size(self):
        got = combine({"a.kicad_pcb": self.A,
                       "b.kicad_pcb": {**self.A, "drawn": "both halves"}})
        self.assertNotIn("width_mm", got)
        self.assertNotIn("drawn", got)
        self.assertIn("a.kicad_pcb", got["unknown"])

    def test_one_unreadable_file_makes_the_design_unknown(self):
        # The missing file could be the larger one; the other's size is not the design's.
        got = combine({"a.kicad_pcb": self.A, "b.kicad_pcb": {"unknown": "HTTPError"}})
        self.assertNotIn("width_mm", got)
        self.assertNotIn("layers", got)
        self.assertIn("b.kicad_pcb", got["unknown"])

    def test_what_a_file_draws_survives_an_unknown_size(self):
        got = combine({"a.kicad_pcb": {"drawn": "both halves", "layers": 2,
                                       "unknown": "joined in one outline"}})
        self.assertEqual((got["drawn"], got["layers"]), ("both halves", 2))
        self.assertIn("joined", got["unknown"])


class PlateRow(unittest.TestCase):
    """A plate's row: what it measures, and whether it is a switch plate."""

    PLATE = {"width_mm": 105.5, "height_mm": 133.71, "layers": 2, "pieces": 1,
             "switch_cutouts": 27, "bbox": [0, 0, 105.5, 133.71], "boxes": []}

    def test_a_plate_with_switch_holes_is_a_switch_plate(self):
        got = plate_row(self.PLATE, pcb=None)
        self.assertEqual(got["role"], "switch plate")
        self.assertNotIn("bbox", got)
        self.assertNotIn("boxes", got)

    def test_a_plate_with_no_switch_holes_says_nothing_of_its_role(self):
        # A bottom plate, a cover, a case layer -- or a switch plate cut as one opening.
        got = plate_row({**self.PLATE, "switch_cutouts": 0}, pcb=None)
        self.assertNotIn("role", got)
        self.assertEqual(got["switch_cutouts"], 0)

    def test_a_plate_drawn_a_quarter_turn_from_its_pcb_is_turned_to_match(self):
        # xenon-e: PCB 133.71 x 105.5, its plates 105.5 x 133.71.
        got = plate_row(self.PLATE, pcb={"width_mm": 133.71, "height_mm": 105.5})
        self.assertEqual((got["width_mm"], got["height_mm"], got["turned"]),
                         (133.71, 105.5, True))

    def test_a_split_switch_plate_with_every_keys_hole_is_both_halves(self):
        # 3w6 cuts both halves' plates in one panel, as it does its PCB.
        got = plate_row({**self.PLATE, "switch_cutouts": 32}, pcb=None, split=True, keys=36)
        self.assertEqual(got["drawn"], "both halves")
        got = plate_row({**self.PLATE, "switch_cutouts": 18}, pcb=None, split=True, keys=36)
        self.assertEqual(got["drawn"], "half")

    def test_a_plate_that_is_not_a_switch_plate_says_nothing_of_halves(self):
        got = plate_row({**self.PLATE, "switch_cutouts": 0}, pcb=None, split=True, keys=36)
        self.assertNotIn("drawn", got)

    def test_a_square_plate_is_left_as_drawn(self):
        got = plate_row({**self.PLATE, "width_mm": 100.0, "height_mm": 100.0},
                        pcb={"width_mm": 100.0, "height_mm": 100.0})
        self.assertNotIn("turned", got)


class Plates(unittest.TestCase):
    """A plate belongs to a design only where its repo holds that one design."""

    SCAN = {
        "one/repo\tpcb/board.kicad_pcb": {"kind": "board"},
        "one/repo\tplate/top-plate.kicad_pcb": {"kind": "no-switch"},
        "one/repo\tcase/bottom.kicad_pcb": {"kind": "no-switch"},
        "one/repo\tFR4 plate/fr4.kicad_pcb": {"kind": "plate"},
        "one/repo\tswitchplate/sw_plate.kicad_pcb": {"kind": "board"},
        "two/repo\ta/plate.kicad_pcb": {"kind": "no-switch"},
    }

    def test_plates_in_a_one_design_repo_are_that_designs(self):
        got = plates_by_slug(self.SCAN, {"solo": {"repo": "one/repo"}},
                             designs_per_repo={"one/repo": 1, "two/repo": 2})
        self.assertEqual(sorted(got["solo"]), ["FR4 plate/fr4.kicad_pcb",
                                               "plate/top-plate.kicad_pcb",
                                               "switchplate/sw_plate.kicad_pcb"])

    def test_a_shared_repos_plates_are_not_guessed(self):
        got = plates_by_slug(self.SCAN, {"x": {"repo": "two/repo"}, "y": {"repo": "two/repo"}},
                             designs_per_repo={"two/repo": 2})
        self.assertEqual(got, {})


class Toml(unittest.TestCase):

    def test_it_round_trips_and_omits_what_is_unknown(self):
        import tomllib
        text = to_toml(
            entries=[{"slug": "a", "drawn": "half", "width_mm": 100.0, "height_mm": 50.0,
                      "layers": 2, "paths": ["x/a.kicad_pcb"]},
                     {"slug": "b", "drawn": "both halves", "unknown": "joined in one outline",
                      "paths": ["b.kicad_pcb"]},
                     {"slug": "c", "drawn": "half", "half_sizes_mm": [[1.5, 2.0], [3.0, 2.0]],
                      "paths": ["l.kicad_pcb", "r.kicad_pcb"]}],
            plates=[{"slug": "a", "path": "p.kicad_pcb", "role": "switch plate",
                     "width_mm": 1.0, "height_mm": 2.0, "turned": True, "layers": 2,
                     "pieces": 1, "switch_cutouts": 29}],
            measured_on="2026-10-04")
        data = tomllib.loads(text)
        self.assertEqual(data["entry"][0]["width_mm"], 100.0)
        self.assertEqual(data["entry"][0]["drawn"], "half")
        self.assertNotIn("width_mm", data["entry"][1])
        self.assertEqual(data["entry"][2]["half_sizes_mm"], [[1.5, 2.0], [3.0, 2.0]])
        self.assertEqual(str(data["entry"][1]["measured_on"]), "2026-10-04")
        self.assertEqual(data["plate"][0]["path"], "p.kicad_pcb")
        self.assertEqual(data["plate"][0]["role"], "switch plate")
        self.assertIs(data["plate"][0]["turned"], True)
        self.assertEqual(data["plate"][0]["switch_cutouts"], 29)


if __name__ == "__main__":
    unittest.main()
