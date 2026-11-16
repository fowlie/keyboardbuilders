# keyboardbuilders

A catalogue of DIY keyboard designs, built from their PCBs and the firmware that runs them.

## Language

**Firmware record**:
One QMK keyboard or one ZMK shield in a firmware tree, named by the tree and its id there (`qmk` + `crkbd/rev1`). It says what keyboard it describes and what it is built for.
_Avoid_: firmware row, ref (a ref is what a design publishes about a record)

**Firmware tree**:
A checkout that holds firmware records, read by one reader (QMK or ZMK): upstream, or a designer's fork. Listed in `seed/trees.toml`.
_Avoid_: repo (a repo holds a design's PCBs)

### How the build finds designs

**Firmware-first**:
The build pass that starts from a firmware record a person has confirmed and linked to its PCB repo, and makes that pair a design.
_Avoid_: Half A

**Board-first**:
The build pass that starts from a PCB no firmware record was linked to, and makes it a design with every record in its repo whose key count fits.
_Avoid_: Half B
