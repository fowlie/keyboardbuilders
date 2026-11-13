package net.keyboardbuilders.keyboardbuilders.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * The 3D render a design has, if it has one. Backlog #60, `ux.md` §3.5.
 *
 * DELIBERATELY THE SAME SHAPE AS [Silhouettes]: a committed seed file read once at
 * startup, keyed by slug. That file is the whole contract between the build-time renderer
 * (`scripts/render_boards.py`, which needs podman, a 6 GB KiCad image and the network) and
 * this page, so the app never renders anything and never learns that it could not.
 *
 * NOT A REPLACEMENT FOR THE SILHOUETTE, and the distinction is the point. The silhouette
 * draws where the KEYS are, which is what makes two boards comparable at a glance; a
 * render draws what the BOARD is -- its outline, its curve, its colour -- which the
 * silhouette cannot show. §3.5's rule for a second image is that it sits BELOW the
 * silhouette and never replaces it, and the owner's own framing was that the render
 * "complements the layout silhouette".
 *
 * ABSENT IS THE COMMON CASE AND MEANS NOTHING IS WRONG. A design has no entry until its
 * board has been rendered and the asset can actually be served, so `of` returning null is
 * "not rendered yet", never "this design has no board". The page shows its silhouette and
 * says nothing, exactly as it did before this existed.
 */
@Component
class Renders(
    mapper: ObjectMapper,
    @Value("classpath:seed/renders.json") resource: Resource,
) {
    /**
     * `path` travels with the entry because a design can have several board files and
     * this is one of them -- 31 designs in the corpus do. #60 refused to let the build
     * pick one silently ("would fabricate a picture of a real board"), so the choice is
     * deterministic AND stated: `alternates` counts the boards not being shown, so the
     * page can be honest rather than implying this is the only one.
     */
    data class Render(
        val glb: String,
        val png: String,
        val path: String,
        val alternates: Int = 0,
        /**
         * The GLB's size in bytes, so the button that downloads it can SAY SO.
         *
         * These models are large -- 13 MB for a 58-key board, 8 MB for a 65 -- and that
         * is a deliberate choice rather than an oversight: the model is loaded only when
         * a reader asks for it, so whoever pays the bytes is the person who wanted them.
         * The number belongs on the button for the same reason: a ten-second wait that
         * was announced reads as a download, and the same wait unannounced reads as a
         * broken page.
         *
         * Defaulted, so a seed written before this field existed still parses.
         */
        val glbBytes: Long = 0,
    )

    private val bySlug: Map<String, Render> =
        resource.inputStream.use { mapper.readValue<Map<String, Render>>(it) }

    fun of(slug: String): Render? = bySlug[slug]

    /** How many designs have one. For the credits/coverage line and for tests. */
    val count: Int get() = bySlug.size
}
