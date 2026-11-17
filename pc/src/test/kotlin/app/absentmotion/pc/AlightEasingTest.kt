package app.absentmotion.pc

import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertEquals
import org.junit.Test

class AlightEasingTest {
    private val presets = listOf(
        "" to listOf(0.694445f to 0.694445f, 0.75f to 0.75f, 0.851667f to 0.851667f, 0.981667f to 0.981667f),
        "cubicBezier 0.42 0.0 1.0 1.0" to listOf(0.694445f to 0.547534f, 0.703889f to 0.55993f, 0.75f to 0.62186f, 0.796111f to 0.686103f, 0.851667f to 0.766595f, 0.907222f to 0.850546f, 0.981667f to 0.969229f),
        "cubicBezier 0.0 0.0 0.58 1.0" to listOf(0.694445f to 0.866127f, 0.703889f to 0.873398f, 0.75f to 0.906535f, 0.796111f to 0.935481f, 0.851667f to 0.964178f, 0.907222f to 0.985241f, 0.981667f to 0.999378f),
        "cubicBezier 0.42 0.0 0.58 1.0" to listOf(0.694445f to 0.805503f, 0.703889f to 0.817502f, 0.75f to 0.870838f, 0.796111f to 0.915041f, 0.851667f to 0.955814f, 0.907222f to 0.983072f, 0.981667f to 0.99936f),
        "bounce 0.3 0.8" to listOf(
            0.694445f to 0.598289f, 0.703889f to 0.664476f, 0.722222f to 0.837165f, 0.75f to 0.8305f, 0.768333f to 0.679818f, 0.796111f to 0.590494f,
            0.805556f to 0.598268f, 0.851667f to 0.914191f, 0.861111f to 0.965858f, 0.879444f to 0.780087f, 0.888889f to 0.719969f, 0.907222f to 0.672343f,
            0.926111f to 0.718635f, 0.935f to 0.77391f, 0.981667f to 1f,
        ),
        "elastic 0.25 1.0 0.5 1.0" to listOf(0.694445f to 1.00274f, 0.75f to 1.00138f, 0.805556f to 1.00032f, 0.879444f to 0.999998f, 0.935f to 0.999998f),
        "cyclic 0.2857143 0.0 0.5 0.0 0.0" to listOf(
            0.694445f to 0.953152f, 0.703889f to 0.986987f, 0.722222f to 0.992405f, 0.75f to 0.853558f, 0.768333f to 0.6865f, 0.796111f to 0.386682f,
            0.805556f to 0.288697f, 0.851667f to 0.003622f, 0.861111f to 0.001902f, 0.879444f to 0.058933f, 0.888889f to 0.116973f, 0.907222f to 0.273774f,
            0.926111f to 0.472952f, 0.935f to 0.570443f, 0.981667f to 0.959907f,
        ),
        "random 0.5 0.5 0.5 0.5 0.5 0.0" to listOf(
            0.694445f to 0.49975f, 0.703889f to 0.848812f, 0.722222f to 1.07236f, 0.75f to 0.573705f, 0.768333f to 0.612353f, 0.796111f to 0.859675f,
            0.805556f to 0.854554f, 0.851667f to 0.693041f, 0.861111f to 0.739483f, 0.879444f to 1.10469f, 0.888889f to 0.9896f, 0.907222f to 0.937429f,
            0.926111f to 0.97276f, 0.935f to 0.968721f, 0.981667f to 1.04734f,
        ),
        "step 0.25 0.0" to listOf(0.694445f to 0.5f, 0.722222f to 0.5f, 0.768333f to 0.75f, 0.981667f to 0.75f),
        "elasticStep 0.2 0.5" to listOf(
            0.694445f to 0.599773f, 0.703889f to 0.59986f, 0.805556f to 0.605261f, 0.851667f to 0.808188f, 0.861111f to 0.804514f, 0.879444f to 0.800219f,
            0.888889f to 0.799791f, 0.981667f to 0.8f,
        ),
    )

    private val variants = listOf(
        "bounce 0.15 0.8" to listOf(0.118471f to 0.26079f, 0.238471f to 0.36572f, 0.382314f to 0.704491f, 0.565412f to 0.846195f),
        "cyclic 0.4 0.2 0.3 0.6 0.5" to listOf(0.339216f to 0.309334f, 0.536902f to 0.986933f, 0.695373f to 0.60375f, 0.834588f to 0.606106f),
        "elastic 0.25 0.3 0.2 1.0" to listOf(0.297686f to 1.46911f, 0.494667f to 0.66519f, 0.652314f to 1.06207f, 0.806902f to 1.05433f),
        "elasticStep 0.3 0.8" to listOf(0.204627f to 0f, 0.396941f to 0.291582f, 0.594588f to 0.3f, 0.791529f to 0.600052f),
        "step 0.3 0.3" to listOf(0.209255f to 0f, 0.367686f to 0.3f, 0.526157f to 0.325521f, 0.713804f to 0.6f),
        "random 0.5 0.5 0.5 0.5 0.5 1.0" to listOf(0.450784f to 0.784934f, 0.623804f to 0.655235f, 0.772314f to 0.224941f, 0.89302f to 0.140117f),
    )

    private fun ease(text: String, u: Float) = if (text.isEmpty()) u else DesktopNative.ease(text, u)

    @Test fun everyPresetFollowsAlightsCurve() {
        for ((text, samples) in presets) for ((u, v) in samples) assertEquals("$text at $u", v, ease(text, u), 1e-4f)
    }

    @Test fun otherSettingsFollowAlightsCurve() {
        for ((text, samples) in variants) for ((u, v) in samples) assertEquals("$text at $u", v, ease(text, u), 1.5e-3f)
    }

    @Test fun fullBouncesThatFitExactlyKeepBouncingToTheEnd() {
        assertEquals(0.0f, ease("bounce 0.4 1.0", 0.8f), 1e-4f)
        assertEquals(0.4225f, ease("bounce 0.2857143 1.0", 0.95f), 1e-3f)
        assertEquals(0.16f, ease("bounce 0.6666667 1.0", 0.8f), 1e-3f)
        assertEquals(1f, ease("bounce 0.3 0.8", 0.97f), 1e-6f)
    }
}
