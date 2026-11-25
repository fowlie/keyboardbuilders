"""Tests for qmk_metadata.joystick_enabled -- owner, 2026-11-25: "Lime also has a Joystick
but it isn't mentioned on the design page"."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from qmk_metadata import joystick_enabled  # noqa: E402


class JoystickEnabled(unittest.TestCase):

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._dir.name)
        (self.root/"kb"/"rev1").mkdir(parents=True)

    def tearDown(self):
        self._dir.cleanup()

    def write(self, rel, text):
        path = self.root/rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def test_the_default_keymap_turning_it_on_counts(self):
        # Lime's own record: keyboards/lime/keymaps/default/rules.mk.
        self.write("kb/keymaps/default/rules.mk", "OLED_ENABLE = yes\nJOYSTICK_ENABLE = yes  # left\n")
        self.assertTrue(joystick_enabled("kb/rev1", self.root))

    def test_the_keyboard_turning_it_on_counts(self):
        self.write("kb/rev1/rules.mk", "JOYSTICK_ENABLE = yes\n")
        self.assertTrue(joystick_enabled("kb/rev1", self.root))

    def test_a_features_entry_counts(self):
        self.write("kb/rev1/keyboard.json", json.dumps({"features": {"joystick": True}}))
        self.assertTrue(joystick_enabled("kb/rev1", self.root))

    def test_off_commented_or_only_a_user_keymap_is_no_evidence(self):
        self.write("kb/rev1/rules.mk", "JOYSTICK_ENABLE = no\n# JOYSTICK_ENABLE = yes\n")
        # One user's keymap is their build, not the board's.
        self.write("kb/keymaps/someone/rules.mk", "JOYSTICK_ENABLE = yes\n")
        self.assertFalse(joystick_enabled("kb/rev1", self.root))


if __name__ == "__main__":
    unittest.main()
