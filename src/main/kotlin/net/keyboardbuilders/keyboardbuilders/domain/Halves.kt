package net.keyboardbuilders.keyboardbuilders.domain

/*
 * Two counts read off a design's own facts, moved here from `DesignPresentation` for
 * #112: the parts module needs the same answers to say how many sockets, diodes and
 * controllers a build takes, and a second copy is how two pages start disagreeing.
 */

/** The key count of the design's largest layout. */
fun Design.largestKeyCount(): Int = core.layouts.maxOf { it.keys.value }

/**
 * WHETHER A SPLIT'S WIRING IS ONE HALF'S, decided by counting and only by counting.
 *
 * Both sources record a split's scan per CONTROLLER, not per design: a ZMK split shield
 * declares the inputs each half's own MCU reads (the Sweep's 17, on a 34-key board), and
 * QMK's `matrix_pins` on a split is the left half's. Printed bare, "17 direct pins" on a
 * 34-key Sweep reads as half its keys unwired, and a 58-key Lily58's "5×6 matrix" as 30
 * positions for 58 switches.
 *
 * But not every split has a controller per half. Cheapino (36 keys, 8×12), Pando (58,
 * 5×12) and bartlepslit (42, 4×12) run both halves from one chip, and their record IS the
 * whole board, so a blanket "per half" on every split would be a new false statement in
 * place of a misleading one. Nothing in the record says which kind a split is (the
 * Charybdis family's split flag is not even in QMK's merged `info.json`; see the note
 * on `rows * cols >= keys` in `Design.kt`). What proves it is arithmetic: a scan with
 * FEWER positions than the board's largest layout has keys cannot be the whole board.
 * Where it could be, the line says nothing extra, which is what it said before.
 * Measured 2026-10-03 on designs.json: every split with wiring falls cleanly on one side
 * (positions < keys <= positions × halves) or is one of those three.
 */
fun Design.isOneHalfOf(wiring: KeyWiring): Boolean {
    if (core.body !is Body.Split) return false
    val positions = when (wiring) {
        is KeyWiring.DirectPin -> wiring.pins.value
        is KeyWiring.Matrix -> wiring.rows.value * wiring.cols.value
        KeyWiring.Analog -> return false
    }
    return positions < largestKeyCount()
}
