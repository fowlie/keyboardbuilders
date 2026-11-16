import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from scan_licences import classify  # noqa: E402


class Classify(unittest.TestCase):
    def test_wtfpl_is_named(self):
        # That-Canadian/MiniDox_PCB's LICENSE.txt opens like this. The owner accepted
        # WTFPL on 2026-11-16; before that it read as unclassified and MiniDox deferred.
        body = (b"            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE\n"
                b"                    Version 2, December 2004\n\n"
                b" Copyright (C) 2004 Sam Hocevar <sam@hocevar.net>\n")
        self.assertEqual(classify(body)[0], "WTFPL")

    def test_mit_is_still_mit(self):
        body = b"MIT License\n\nPermission is hereby granted, free of charge, to any person"
        self.assertEqual(classify(body)[0], "MIT")


if __name__ == "__main__":
    unittest.main()
