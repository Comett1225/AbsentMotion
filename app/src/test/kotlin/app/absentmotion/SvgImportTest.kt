package app.absentmotion

import app.absentmotion.i18n.txt
import app.absentmotion.model.Layer
import app.absentmotion.model.MaxSvgNodes
import app.absentmotion.model.Pt
import app.absentmotion.model.Scene
import app.absentmotion.model.SvgImport
import app.absentmotion.model.contours
import app.absentmotion.model.importSvg
import app.absentmotion.model.parseColor
import app.absentmotion.model.parseSvgColor
import app.absentmotion.model.parseSvgPath
import app.absentmotion.model.perpendicularAxis
import app.absentmotion.model.signedArea
import app.absentmotion.model.withContours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SvgImportTest {
    private val host = Scene.create("Test", 1000, 1000, 30, 0)

    private fun import(svg: String): SvgImport = importSvg(svg.trimIndent(), "Logo", host, 0, 2000)

    private fun shapes(layer: Layer): List<Layer> =
        if (layer.kind == "embedScene") layer.nested!!.layers.flatMap(::shapes) else listOf(layer)

    private fun points(layer: Layer): List<Pt> {
        val at = layer.location
        return layer.contours!!.flatMap { c -> c.knots.map { Pt(it.p.x + at[0], it.p.y + at[1]) } }
    }

    private fun assertHas(points: List<Pt>, x: Float, y: Float) =
        assertTrue("no point near ($x, $y) in $points", points.any { abs(it.x - x) < 0.6f && abs(it.y - y) < 0.6f })

    @Test fun aStrokedLineIconBecomesAGroupOfStrokeOnlyShapes() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="icon">
              <path d="M5 12h14" />
              <path d="M12 5v14" />
              <circle cx="12" cy="12" r="10" />
            </svg>
        """)
        val top = result.layer!!
        assertEquals("embedScene", top.kind)
        assertEquals("Logo", top.label)
        val parts = shapes(top)
        assertEquals(3, parts.size)
        assertEquals(3, result.shapes)
        assertTrue(result.skipped.isEmpty())
        for (part in parts) {
            assertEquals("none", part.fillType)
            val stroke = part.node.child("path-stroke")!!
            assertEquals("round", stroke["cap"])
            assertEquals("round", stroke["join"])
            assertEquals("#ff000000", stroke.child("color")!!["value"])
            assertEquals(2f * 800f / 24f, stroke.child("size")!!["value"]!!.toFloat(), 0.01f)
        }
        val s = 800f / 24f
        assertHas(points(parts[0]), 100 + 5 * s, 100 + 12 * s)
        assertHas(points(parts[0]), 100 + 19 * s, 100 + 12 * s)
        assertHas(points(parts[2]), 100 + 22 * s, 500f)
        assertEquals(0, top.nested!!.layers.first().startTime)
        assertEquals(2000, top.nested!!.layers.first().endTime)
    }

    @Test fun anIllustratorLogoKeepsClassStylesGradientsAndReportsText() {
        val result = import("""
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Generator: a drawing program -->
            <svg version="1.1" id="Layer_1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px"
                 viewBox="0 0 100 100" style="enable-background:new 0 0 100 100;" xml:space="preserve">
            <style type="text/css">
                .st0{fill:#E30613;}
                .st1{fill:url(#SVGID_1_);}
                .st2{font-family:'Arial';}
            </style>
            <g id="Logo">
                <rect x="10" y="10" class="st0" width="80" height="40" rx="8"/>
                <linearGradient id="SVGID_1_" gradientUnits="userSpaceOnUse" x1="0" y1="80" x2="100" y2="80">
                    <stop offset="0" style="stop-color:#FFFFFF"/>
                    <stop offset="0.5" style="stop-color:#888888"/>
                    <stop offset="1" style="stop-color:#000000"/>
                </linearGradient>
                <polygon class="st1" points="0,100 50,60 100,100 "/>
                <text transform="matrix(1 0 0 1 20 95)" class="st2">BRAND</text>
            </g>
            </svg>
        """)
        val top = result.layer!!
        assertEquals("Logo", top.label)
        val (rect, triangle) = shapes(top)
        assertEquals("color", rect.fillType)
        assertEquals(0xFFE30613.toInt(), rect.fillColor)
        assertEquals(8, rect.contours!!.single().knots.size)
        assertHas(points(rect), 100 + 18 * 8f, 180f)
        assertEquals("gradient", triangle.fillType)
        val g = triangle.node.child("gradient")!!
        assertEquals("linear", g["type"])
        assertEquals("#ffffffff", g["startColor"])
        assertEquals("#ff000000", g["endColor"])
        assertEquals(0f, g["start"]!!.split(',')[0].toFloat(), 1e-3f)
        assertEquals(1f, g["end"]!!.split(',')[0].toFloat(), 1e-3f)
        assertTrue(result.skipped.contains(txt(R.string.model_svg_skipped_text)))
        assertTrue(result.skipped.contains(txt(R.string.model_svg_skipped_gradient_colours)))
    }

    @Test fun groupTransformsAreAppliedToTheShapes() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">
              <g transform="translate(50,50) rotate(90)"><rect width="10" height="10" fill="#00f"/></g>
              <g transform="matrix(2 0 0 2 0 0)"><rect x="1" y="1" width="5" height="5"/></g>
              <rect transform="skewX(45)" x="0" y="90" width="10" height="10"/>
              <rect transform="rotate(90 80 80)" x="80" y="80" width="10" height="4"/>
            </svg>
        """)
        val (turned, scaled, skewed, pivoted) = shapes(result.layer!!)
        assertHas(points(turned), 500f, 580f)
        assertHas(points(turned), 420f, 580f)
        assertHas(points(scaled), 116f, 116f)
        assertHas(points(scaled), 196f, 196f)
        assertHas(points(skewed), 100 + 90 * 8f, 820f)
        assertHas(points(pivoted), 740f, 820f)
    }

    @Test fun arcsCirclesEllipsesLinesAndPolylinesBecomePaths() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <path d="M10 50 A40 40 0 0 1 90 50 Z" fill="red"/>
              <ellipse cx="50" cy="80" rx="20" ry="10" fill="green"/>
              <line x1="0" y1="0" x2="100" y2="100" stroke="black" stroke-width="2"/>
              <polyline points="0 100, 50 90 100 100" fill="none" stroke="#123456"/>
            </svg>
        """)
        val (arc, ellipse, line, polyline) = shapes(result.layer!!)
        assertHas(points(arc), 100 + 50 * 8f, 180f)
        assertHas(points(arc), 180f, 500f)
        assertTrue(arc.contours!!.single().closed)
        assertHas(points(ellipse), 100 + 70 * 8f, 100 + 80 * 8f)
        assertHas(points(ellipse), 500f, 100 + 90 * 8f)
        assertEquals("none", line.fillType)
        assertHas(points(line), 900f, 900f)
        assertEquals("none", polyline.fillType)
        assertEquals("#ff123456", polyline.node.child("path-stroke")!!.child("color")!!["value"])
        assertTrue(!polyline.contours!!.single().closed)
    }

    @Test fun symbolsAreDrawnWhereTheyAreUsed() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100">
              <defs><symbol id="dot" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5" fill="#f80"/></symbol></defs>
              <use xlink:href="#dot" x="20" y="20" width="20" height="20"/>
            </svg>
        """)
        val dot = result.layer!!
        assertEquals("shape", dot.kind)
        assertEquals(0xFFFF8800.toInt(), dot.fillColor)
        assertHas(points(dot), 100 + 40 * 8f, 100 + 30 * 8f)
        assertHas(points(dot), 100 + 30 * 8f, 100 + 20 * 8f)
    }

    @Test fun fillRulesBecomeExcludedContours() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <path fill-rule="evenodd" d="M0 0H100V100H0Z M25 25H75V75H25Z"/>
              <path d="M0 0H100V100H0Z M25 25V75H75V25Z"/>
              <path d="M0 0H100V100H0Z M25 25H75V75H25Z"/>
              <path d="M0 0H100V100H0Z M25 25V75H75V25Z" stroke="red"/>
            </svg>
        """)
        val (evenOdd, hole, filled, stroked) = shapes(result.layer!!)
        assertEquals(listOf(false, true), evenOdd.contours!!.map { it.exclude })
        assertEquals(listOf(false, true), hole.contours!!.map { it.exclude })
        assertEquals(listOf(false, false), filled.contours!!.map { it.exclude })
        assertNotNull(stroked.node.child("path")!!["d"])
        assertEquals(2, stroked.contours!!.size)
    }

    @Test fun opacityAndAspectRatioFollowTheSpec() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" width="100" height="100">
              <g opacity="0.5">
                <rect width="100" height="50" fill="blue" fill-opacity="0.5"/>
                <rect width="10" height="10" style="fill: rgb(255, 0, 0); opacity: .25"/>
              </g>
            </svg>
        """)
        val group = result.layer!!
        assertEquals(0.5f, group.opacity, 1e-4f)
        val (wide, small) = shapes(group)
        assertEquals(0x7F, wide.fillColor ushr 24)
        assertHas(points(wide), 100f, 300f)
        assertHas(points(wide), 900f, 700f)
        assertEquals(0.25f, small.opacity, 1e-4f)
        assertEquals(0xFFFF0000.toInt(), small.fillColor)
        val stretched = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" width="100" height="100" preserveAspectRatio="none">
              <rect width="100" height="50"/>
            </svg>
        """).layer!!
        assertHas(points(stretched), 900f, 900f)
    }

    @Test fun radialGradientsUseTheShapeBox() {
        val layer = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
              <radialGradient id="glow"><stop offset="0" stop-color="#fff"/><stop offset="100%" stop-color="#000" stop-opacity="0"/></radialGradient>
              <circle cx="5" cy="5" r="5" fill="url(#glow)"/>
            </svg>
        """).layer!!
        val g = layer.node.child("gradient")!!
        assertEquals("radial", g["type"])
        assertEquals("0.50000,0.50000", g["start"])
        assertEquals("1.00000,0.50000", g["end"])
        assertEquals(0, parseColor(g["endColor"])!! ushr 24)
    }

    @Test fun unsupportedPartsAreReportedOnce() {
        val result = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
              <defs><filter id="f"/><mask id="m"/><clipPath id="c"><rect width="5" height="5"/></clipPath></defs>
              <image href="photo.png" width="10" height="10"/>
              <text x="0" y="5">Hi</text><text x="0" y="9">There</text>
              <rect width="5" height="5" filter="url(#f)" mask="url(#m)" clip-path="url(#c)"/>
              <rect x="5" width="5" height="5" display="none"/>
            </svg>
        """)
        assertEquals("shape", result.layer!!.kind)
        assertEquals(listOf(R.string.model_svg_skipped_images, R.string.model_svg_skipped_text, R.string.model_svg_skipped_clipping, R.string.model_svg_skipped_masks, R.string.model_svg_skipped_filters).map { txt(it) }, result.skipped)
    }

    @Test fun anEmptyOrForeignFileIsRejected() {
        assertNull(import("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>only words</text></svg>""").layer)
        val error = runCatching { import("<scene title=\"x\"/>") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun svgColoursParse() {
        assertEquals(0xFFAABBCC.toInt(), parseSvgColor("#abc"))
        assertEquals(0x44112233, parseSvgColor("#11223344"))
        assertEquals(0xFFFF0000.toInt(), parseSvgColor("rgb(255, 0, 0)"))
        assertEquals(0xFFFF0000.toInt(), parseSvgColor("rgb(100%, 0%, 0%)"))
        assertEquals(0x7F0000FF, parseSvgColor("rgba(0,0,255,0.5)"))
        assertEquals(0xFFFF6347.toInt(), parseSvgColor("Tomato"))
        assertNull(parseSvgColor("nonsense"))
        assertNull(parseSvgColor("#12"))
    }

    @Test fun nestedUseFanOutStopsInsteadOfRunningForever() {
        val defs = StringBuilder("<defs><g id=\"a0\"><path d=\"M0 0L1 1\" stroke=\"black\"/></g>")
        for (i in 1..9) defs.append("<g id=\"a$i\">").append((1..10).joinToString("") { "<use href=\"#a${i - 1}\"/>" }).append("</g>")
        defs.append("</defs>")
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">$defs<use href=\"#a9\"/></svg>"
        var result: SvgImport? = null
        val worker = Thread { result = importSvg(svg, "t", host, 0, 1000) }.apply { isDaemon = true; start() }
        worker.join(15_000)
        assertTrue("a ${svg.length}-byte SVG is still importing after 15 s", !worker.isAlive)
        assertEquals(1000, result!!.shapes)
        assertTrue(result!!.skipped.contains(txt(app.absentmotion.R.string.model_svg_skipped_shapes, "1000")))
        assertTrue(result!!.skipped.contains(txt(app.absentmotion.R.string.library_text_value, "parts repeated too many times")))
    }

    @Test fun anSvgWithTooManyElementsIsRefusedWhileParsing() {
        val head = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
        val fits = runCatching { import(head + "<g/>".repeat(MaxSvgNodes - 1) + "</svg>") }
        assertTrue(fits.exceptionOrNull().toString(), fits.isSuccess)
        val error = runCatching { import(head + "<g/>".repeat(MaxSvgNodes) + "</svg>") }.exceptionOrNull()
        assertTrue(error.toString(), error is IllegalArgumentException)
    }

    private val open = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"

    private fun importTimed(svg: String, limitMs: Long = 5_000): SvgImport {
        var result: SvgImport? = null
        var failure: Throwable? = null
        val started = System.nanoTime()
        val worker = Thread { try { result = importSvg(svg, "t", host, 0, 1000) } catch (e: Throwable) { failure = e } }.apply { isDaemon = true; start() }
        worker.join(30_000)
        assertTrue("a ${svg.length}-character SVG is still importing after 30 s", !worker.isAlive)
        failure?.let { throw it }
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue("importing a ${svg.length}-character SVG took $took ms", took < limitMs)
        return result!!
    }

    @Test fun anEvenOddPathKeepsItsRuleEvenWithASingleSelfCrossingContour() {
        val star = "M50 5L80 95L5 35L95 35L20 95Z"
        val even = import("$open<path fill-rule=\"evenodd\" d=\"$star\"/></svg>").layer!!
        assertEquals("evenodd", even.node.child("path")!!["fillRule"])
        assertEquals(1, even.contours!!.size)
        assertNull(import("$open<path d=\"$star\"/></svg>").layer!!.node.child("path")!!["fillRule"])
        assertEquals("evenodd", even.withContours(even.contours!!).node.child("path")!!["fillRule"])
    }

    @Test fun aStrokeOnlyPathHasNoFillRule() {
        val line = import("$open<path fill=\"none\" stroke=\"red\" fill-rule=\"evenodd\" d=\"M0 0L50 50\"/></svg>").layer!!
        assertNull(line.node.child("path")!!["fillRule"])
    }

    @Test fun aHoleMadeOfTwoCurvedAnchorsIsStillRecognisedAsAHole() {
        val hole = "M30 50 C30 110 70 110 70 50 C70 -10 30 -10 30 50 Z"
        val ring = parseSvgPath(hole).single()
        assertEquals(2, ring.knots.size)
        val area = ring.signedArea()
        assertTrue("area $area", area < -2_000 && area > -3_500)
        val layer = import("$open<path d=\"M0 0H100V100H0Z $hole\"/></svg>").layer!!
        assertEquals(listOf(false, true), layer.contours!!.map { it.exclude })
        val sameWay = import("$open<path d=\"M0 0H100V100H0Z M30 50 C30 -10 70 -10 70 50 C70 110 30 110 30 50 Z\"/></svg>").layer!!
        assertEquals(listOf(false, false), sameWay.contours!!.map { it.exclude })
    }

    @Test fun aFillOnlyPathWithManySubpathsIsOnePathNodeAndKeepsItsHoles() {
        val d = "M0 0H100V100H0Z M10 10V40H40V10Z M60 60V90H90V60Z M45 45H55V55H45Z"
        val layer = import("$open<path d=\"$d\"/></svg>").layer!!
        val path = layer.node.child("path")!!
        assertTrue(path.children.none { it.name == "contour" })
        assertEquals(4, path["d"]!!.count { it == 'M' })
        assertEquals(listOf(false, true, true, false), layer.contours!!.map { it.exclude })
        val edited = layer.withContours(layer.contours!!)
        assertEquals(listOf(false, true, true, false), edited.contours!!.map { it.exclude })
    }

    @Test fun anEvenOddRingOfSubpathsAlternates() {
        val layer = import("$open<path fill-rule=\"evenodd\" d=\"M0 0H100V100H0Z M10 10H90V90H10Z M20 20H80V80H20Z\"/></svg>").layer!!
        assertEquals(listOf(false, true, true), layer.contours!!.map { it.exclude })
    }

    @Test fun aHugeTransformNeverWritesANonNumber() {
        val result = import("""
            $open
              <g transform="scale(1e30)"><path d="M0 0L10 10" stroke="red" transform="scale(1e30)"/></g>
              <rect width="10" height="10" stroke="red" stroke-width="1e30" transform="scale(1e30 1e-30)"/>
              <rect width="5" height="5" transform="scale(1e50)"/>
              <circle cx="50" cy="50" r="20" fill="url(#g)" transform="scale(1e20)"/>
              <linearGradient id="g" gradientTransform="scale(1e30)"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
              <rect x="10" y="10" width="30" height="30"/>
            </svg>
        """)
        val text = result.layer!!.node.toXml()
        assertTrue(text, !text.contains("NaN") && !text.contains("Infinity"))
    }

    @Test fun styleTextWithoutRulesDoesNotTakeQuadraticTime() {
        val plain = importTimed("$open<style>${"x ".repeat(100_000)}</style><rect width=\"10\" height=\"10\"/></svg>")
        assertEquals(1, plain.shapes)
        importTimed("$open<style>${"a{".repeat(50_000)}</style><rect width=\"10\" height=\"10\"/></svg>")
        importTimed("$open<style>${("/" + "*").repeat(100_000)}</style><rect width=\"10\" height=\"10\"/></svg>")
        importTimed("$open<style>${"@x;".repeat(100_000)}</style><rect width=\"10\" height=\"10\"/></svg>")
        importTimed("$open<style>${"}".repeat(200_000)}</style><rect width=\"10\" height=\"10\"/></svg>")
    }

    @Test fun manyRulesAndManyElementsDoNotMultiply() {
        val rules = (0 until 20_000).joinToString("") { ".c$it{fill:#ff0000}" }
        val shapes = (0 until 1_000).joinToString("") { "<rect class=\"c${it * 17}\" width=\"1\" height=\"1\"/>" }
        val big = importTimed("$open<style>$rules</style>$shapes<g>${"<g/>".repeat(60_000)}</g></svg>")
        assertEquals(1000, big.shapes)
        assertEquals(0xFFFF0000.toInt(), shapes(big.layer!!)[0].fillColor)
    }

    @Test fun cssAtRulesDoNotSwallowOrOverrideTheRulesAroundThem() {
        val layer = import("""
            $open
              <style>
                @charset "utf-8";
                @import url(other.css);
                .a { fill: #ff0000 }
                @media (prefers-color-scheme: dark) { .a { fill: #0000ff } .b { fill: #0000ff } }
                @keyframes spin { from { fill: #00ff00 } to { fill: #00ff00 } }
                @font-face { font-family: x; src: url(y.woff) }
                .b { fill: #00ff00 }
                /* .c { fill: #123456 } */
                .d { fill: #ffff00 }
              </style>
              <rect class="a" width="10" height="10"/>
              <rect class="b" width="10" height="10"/>
              <rect class="c" width="10" height="10"/>
              <rect class="d" width="10" height="10"/>
            </svg>
        """).layer!!
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF000000.toInt(), 0xFFFFFF00.toInt()), shapes(layer).map { it.fillColor })
    }

    @Test fun percentLengthsUseTheViewBoxNotTheOuterSize() {
        val layer = import("""
            <svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 100 100">
              <rect width="100%" height="100%" fill="#123456"/>
            </svg>
        """).layer!!
        val corners = points(layer)
        assertHas(corners, 100f, 100f)
        assertHas(corners, 900f, 900f)
        assertTrue(corners.toString(), corners.all { it.x in 99f..901f && it.y in 99f..901f })
        val plain = import("""<svg xmlns="http://www.w3.org/2000/svg" width="50%" viewBox="0 0 100 100"><circle cx="50%" cy="50%" r="25%"/></svg>""").layer!!
        assertHas(points(plain), 600f, 500f)
    }

    @Test fun contentNestedTooDeeplyIsReported() {
        val deep = "<g>".repeat(70) + "<rect width=\"5\" height=\"5\"/>" + "</g>".repeat(70)
        val result = import("$open$deep<rect width=\"9\" height=\"9\"/></svg>")
        assertEquals(1, result.shapes)
        assertTrue(result.skipped.contains(txt(R.string.library_text_value, "parts nested too deeply")))
    }

    @Test fun aDiagonalUserSpaceGradientStaysPerpendicularOnAWideShape() {
        val layer = import("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
              <linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="100" y2="100"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
              <linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
              <rect width="200" height="100" fill="url(#g)"/>
              <rect width="200" height="100" fill="url(#b)"/>
            </svg>
        """).layer!!
        val (user, box) = shapes(layer).map { it.node.child("gradient")!! }
        assertEquals("0.00000,0.00000", user["start"])
        assertEquals("0.80000,0.40000", user["end"])
        assertEquals("1.00000,1.00000", box["end"])
    }

    @Test fun anAxisAlignedGradientIsNotChanged() {
        val flat = perpendicularAxis(Pt(0.3f, 0f), 800f, 400f)
        assertEquals(0.3f, flat.x, 1e-5f)
        assertEquals(0f, flat.y, 1e-6f)
        val square = perpendicularAxis(Pt(0.5f, 0.5f), 300f, 300f)
        assertEquals(0.5f, square.x, 1e-5f)
        assertEquals(0.5f, square.y, 1e-5f)
    }

    @Test fun hslColoursParse() {
        assertEquals(0xFF00FF00.toInt(), parseSvgColor("hsl(120, 100%, 50%)"))
        assertEquals(0x7FFF0000, parseSvgColor("hsl(0 100% 50% / 0.5)"))
        assertEquals(0xFF808080.toInt(), parseSvgColor("hsl(200, 0%, 50%)"))
        assertEquals(0xFF0000FF.toInt(), parseSvgColor("hsla(240deg, 100%, 50%, 1)"))
        assertNull(parseSvgColor("hsl(nope)"))
    }
}
