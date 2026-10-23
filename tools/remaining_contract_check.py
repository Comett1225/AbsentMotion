#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parent.parent
REVISION = None
if len(sys.argv) > 2 and sys.argv[1] == '--revision':
    REVISION = sys.argv[2]
    del sys.argv[1:3]


def source(path):
    if REVISION:
        return subprocess.check_output(['git', 'show', REVISION + ':' + path], cwd=ROOT, text=True)
    return (ROOT / path).read_text()


def kotlin(path):
    return source('app/src/main/kotlin/app/absentmotion/' + path)


def english(key):
    text = source('app/src/main/res/values/strings.xml')
    return re.search(r'<string name="' + key + r'"[^>]*>(.*?)</string>', text, re.S).group(1)


class RemainingContracts(unittest.TestCase):
    def test_wheel_scrubbing_settles_after_the_last_event(self):
        text = kotlin('ui/editor/Timeline.kt')
        self.assertIn('\n                view.finishWheelScrub()\n', text)
        self.assertIn('fun finishWheelScrub() { motion = scope.launch { kotlinx.coroutines.delay(120); settle() } }', text)

    def test_test_intents_handle_closed_engines_and_preserve_cancellation(self):
        text = source('app/src/debug/kotlin/app/absentmotion/TestHooks.kt')
        self.assertIn('if (e.message != "engine closed") throw e', text)
        self.assertIn('catch (e: kotlinx.coroutines.CancellationException)', text)

    def test_border_state_lives_above_the_easing_navigation(self):
        text = kotlin('ui/editor/LayerTools.kt')
        self.assertIn('BorderShadowLifetime(state, selected.id, panel)', text)
        self.assertLess(text.index('BorderShadowLifetime'), text.index('if (state.easingOpen)'))
        text = kotlin('ui/editor/panels/BorderShadowPanel.kt')
        self.assertIn('DisposableEffect(owner, layerId, panel)', text)
        panel = text.split('internal fun BorderShadowPanel', 1)[1].split('private fun editColour', 1)[0]
        self.assertNotIn('onDispose', panel)

    def test_nested_fonts_are_resolved_for_preview_and_explicit_exports(self):
        text = kotlin('engine/EngineThread.kt')
        body = text.split('fun setScene(next: Scene, xml: String = next.toXml())', 1)[1].split('fun refreshMedia', 1)[0]
        self.assertIn('next.fontNames()', body)
        self.assertNotIn('Scene.parse(', body)
        self.assertGreaterEqual(text.count('document.fontNames().forEach'), 2)
        text = kotlin('fonts/TextLayout.kt')
        self.assertIn('scene.mapTimedLayers(fps, timeMs.toDouble())', text)

    def test_gif_storage_gate_uses_an_encoded_measurement(self):
        text = kotlin('ui/export/ExportScreen.kt')
        self.assertIn('val measured = measureGif(engine, exported, s).bytes', text)
        self.assertIn('if (storageStatus(measured, free) is StorageStatus.NotEnough)', text)
        self.assertIn('if (kind != ExportKind.Gif && storageStatus(estimate, free) is StorageStatus.NotEnough)', text)

    def test_interpolation_errors_cannot_silently_choose_a_blend_or_plain_frame(self):
        text = source('app/src/main/cpp/engine.cpp')
        self.assertIn('if (flow && !field)', text)
        self.assertIn('if (!mixed)', text)
        self.assertNotIn('if (mixed) image = std::move(mixed)', text)
        text = kotlin('media/VideoLibrary.kt')
        self.assertIn('throw ExportError(txt(R.string.media_error_interp_render, txt(request.smoothing.label)))', text)
        self.assertIn('could not render the requested video frame', english('media_error_interp_render'))
        exact = text.split('suspend fun exact(', 1)[1].split('private suspend fun decode', 1)[0]
        self.assertIn('throw e', exact)
        self.assertNotIn('if (image.timestamp / 1000 != pts) return false', text)

    def test_nested_video_selection_uses_each_track_local_scene(self):
        text = kotlin('media/VideoLibrary.kt')
        self.assertIn('request.scene.videoSamples(', text)
        self.assertIn('val tracks = layerTracks()', text)
        self.assertIn('sample.timeMs + ahead', text)
        self.assertIn('sample.scene', text)

    def test_unused_receive_controls_are_removed_from_both_home_layouts(self):
        for path in ['ui/home/HomeScreen.kt', 'ui/home/WideHome.kt', 'ui/home/HomeIcons.kt']:
            text = kotlin(path)
            self.assertNotIn('onReceive', text)
            self.assertNotIn('Receive from phone', text)
            self.assertNotIn('val Receive =', text)
        self.assertIn('WideHeader(', kotlin('ui/home/HomeScreen.kt'))


if __name__ == '__main__':
    unittest.main()
