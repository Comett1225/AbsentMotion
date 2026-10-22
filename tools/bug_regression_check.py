#!/usr/bin/env python3
import ast
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent

class ToolRegressions(unittest.TestCase):
    def test_font_generator_output_is_publishable_and_checkable(self):
        catalogue = ROOT / 'app/src/main/kotlin/app/absentmotion/fonts/FontCatalog.kt'
        self.assertIsNone(re.search(r'^\s*(//|/\*|\*)', catalogue.read_text(), re.M))
        result = subprocess.run([sys.executable, str(ROOT / 'tools/gen_fonts.py'), '--check'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    @unittest.skipUnless((ROOT / 'tools/publish_forbidden.txt').exists(), 'tools/publish_forbidden.txt is private and not in this checkout')
    def test_relative_apk_is_resolved_from_the_callers_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            with zipfile.ZipFile(Path(folder) / 'fixture.apk', 'w') as apk:
                apk.writestr('assets/fixture.txt', 'fixture')
            result = subprocess.run([str(ROOT / 'tools/publish_check.sh'), '--apk', './fixture.apk'], cwd=folder, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('-- inside ./fixture.apk', result.stdout)

    def test_patch_conflicts_abort_but_applied_patches_are_idempotent(self):
        source = (ROOT / 'tools/build_skia.sh').read_text()
        loop = source[source.index('for p in '):source.index('\nGN=')]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / 'tools/skia-patches').mkdir(parents=True)
            (folder / 'tools/skia-patches/fix.patch').touch()
            fake = folder / 'git'
            fake.write_text('#!/bin/sh\ncase "$*" in *--reverse*) exit "${REVERSE_STATUS}";; *--check*) exit "${CHECK_STATUS}";; *) exit 0;; esac\n')
            fake.chmod(0o755)
            env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'], ROOT=str(folder))
            for check, reverse, expected in [(0, 1, 0), (1, 0, 0), (1, 1, 1)]:
                result = subprocess.run(['sh', '-ec', loop], env=dict(env, CHECK_STATUS=str(check), REVERSE_STATUS=str(reverse)), capture_output=True)
                self.assertEqual(expected, int(result.returncode != 0))

    def test_shader_compile_errors_abort_the_build(self):
        source = (ROOT / 'tools/sksl_check/build.sh').read_text()
        loop = source[source.index('for f in '):source.index('\nclang++ $FLAGS -c "$HERE')]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / 'obj').mkdir()
            compiler = folder / 'clang++'
            compiler.write_text('#!/bin/sh\nexit 1\n')
            compiler.chmod(0o755)
            env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'], OUT=str(folder), SKIA=str(folder), SRC='invalid.cpp', FLAGS='')
            self.assertNotEqual(0, subprocess.run(['sh', '-ec', loop], env=env, capture_output=True).returncode)

    def test_cached_touch_dex_is_sent_to_each_device(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / 'tools').mkdir()
            script = folder / 'tools/touch.sh'
            script.write_text((ROOT / 'tools/touch.sh').read_text())
            (folder / 'tools/env.sh').write_text((ROOT / 'tools/env.sh').read_text())
            (folder / 'tools/Touch.java').write_text('fixture')
            (folder / 'app/build/touch').mkdir(parents=True)
            (folder / 'app/build/touch/classes.dex').touch()
            sdk = folder / 'Library/Android/sdk/platform-tools'
            sdk.mkdir(parents=True)
            adb = sdk / 'adb'
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TOUCH_LOG"\n')
            adb.chmod(0o755)
            log = folder / 'calls'
            env = {k: v for k, v in os.environ.items() if k not in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ADB', 'SDK_ROOT')}
            env.update(HOME=str(folder), TOUCH_LOG=str(log))
            for target in ['emulator-5554', 'emulator-5556']:
                subprocess.run(['sh', str(script)], env=dict(env, ANDROID_SERIAL=target), check=True, capture_output=True)
            calls = log.read_text()
            for target in ['emulator-5554', 'emulator-5556']:
                self.assertIn('-s ' + target + ' push', calls)

    def test_export_timing_reports_failures_and_zero_elapsed_time(self):
        with tempfile.TemporaryDirectory() as folder:
            scene = Path(folder) / 'scene.xml'
            scene.write_text('<scene fps="30" totalTime="999"/>')
            for logs in ['', '1.0 I AMEngine: attached color space 1\n', '1.0 I AMEngine: attached color space 1\n1.0 I AMExport: done\n', '1.0 I AMEngine: attached color space 1\n2.0 E AMExport: failed\n']:
                helper = ROOT / 'tools/export_timing.py'
                if helper.exists():
                    cmd = [sys.executable, str(helper), str(scene)]
                else:
                    script = (ROOT / 'tools/export_speed.sh').read_text().split('python3 -c "', 1)[1].rsplit('"', 1)[0].replace('$SCENE', str(scene))
                    cmd = [sys.executable, '-c', script]
                result = subprocess.run(cmd, input=logs, text=True, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('Traceback', result.stderr)
            if helper.exists():
                result = subprocess.run([sys.executable, str(helper), str(scene)], input='1.0 I AMEngine: attached color space 1\n3.0 I AMExport: done\n', text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('30 frames in 2.0 s', result.stdout)

    def test_python_policy_rejects_docstrings_but_allows_data_strings(self):
        scanner = ROOT / 'tools/source_policy.py'
        self.assertTrue(scanner.exists())
        spec = importlib.util.spec_from_file_location('source_policy', scanner)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        quote = chr(34) * 3
        for source in [quote + 'module' + quote, 'def f():\n    ' + quote + 'function' + quote, 'value = 1\n' + chr(35) + ' note\n']:
            self.assertTrue(module.violations(source))
        self.assertFalse(module.violations('data = ' + quote + 'payload' + quote))
        self.assertFalse(module.violations('#!/usr/bin/env python3\nvalue = 1\n'))

if __name__ == '__main__':
    unittest.main()
