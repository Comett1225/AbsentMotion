#!/usr/bin/env python3
import ast
import importlib.util
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent

class ToolRegressions(unittest.TestCase):
    def test_font_generator_output_is_publishable_and_checkable(self):
        catalogue = ROOT / 'shared/kotlin/app/absentmotion/fonts/FontCatalog.kt'
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
            (folder / 'android/build/touch').mkdir(parents=True)
            (folder / 'android/build/touch/classes.dex').touch()
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

    def test_comment_check_fails_when_git_cannot_list_the_files(self):
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / 'git'
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'])
            for script, message in [('#!/bin/sh\nexit 128\n', 'no file was checked'), ('#!/bin/sh\nexit 0\n', 'nothing was checked')]:
                fake.write_text(script)
                fake.chmod(0o755)
                result = subprocess.run([sys.executable, str(ROOT / 'tools/comment_check.py')], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_comment_check_rejects_python_docstrings(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            source = folder / 'fixture.py'
            source.write_text('"""A module description."""\nvalue = 1\n')
            git = folder / 'git'
            git.write_text('#!/bin/sh\nprintf "%s\\n" ' + shlex.quote(str(source)) + '\n')
            git.chmod(0o755)
            result = subprocess.run([sys.executable, str(ROOT / 'tools/comment_check.py')],
                                    env=dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH']), capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('fixture.py:1:', result.stdout)

    @unittest.skipUnless((ROOT / 'tools/publish_forbidden.txt').exists(), 'tools/publish_forbidden.txt is private and not in this checkout')
    def test_publish_check_fails_when_a_scanner_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            python = folder / 'python3'
            env = dict(os.environ, PATH=str(folder) + os.pathsep + os.environ['PATH'])
            for scanner in ['tools/publish_scan.py', 'tools/comment_check.py']:
                python.write_text('#!/bin/sh\nif [ "$1" = ' + shlex.quote(scanner) + ' ]; then exit 17; fi\nexec ' + shlex.quote(sys.executable) + ' "$@"\n')
                python.chmod(0o755)
                result = subprocess.run(['sh', str(ROOT / 'tools/publish_check.sh')], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode, scanner + '\n' + result.stdout + result.stderr)
                self.assertNotIn('publish_check: nothing found.', result.stdout)

    def test_publish_check_reads_odd_names_and_links_and_refuses_other_identities(self):
        with tempfile.TemporaryDirectory() as folder:
            repo = Path(folder)
            (repo / 'tools').mkdir()
            for name in ['publish_check.sh', 'publish_scan.py', 'comment_check.py', 'source_policy.py']:
                shutil.copy(ROOT / 'tools' / name, repo / 'tools' / name)
            (repo / 'tools/publish_forbidden.txt').write_text('fixtureword\n')
            (repo / 'tools/publish_allowed.txt').write_text('https://example.invalid/fixtureword/app\n')
            (repo / '.gitignore').write_text('/tools/publish_forbidden.txt\n/tools/publish_allowed.txt\n')
            (repo / 'page.txt').write_text('see https://example.invalid/FixtureWord/app\n')
            env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
            def git(*args, name='AbsentMotion', mail='noreply@absentmotion.invalid'):
                subprocess.run(['git', '-C', str(repo), '-c', 'user.name=' + name, '-c', 'user.email=' + mail, '-c', 'commit.gpgsign=false', *args], check=True, capture_output=True, env=env)
            def check():
                result = subprocess.run(['sh', str(repo / 'tools/publish_check.sh')], capture_output=True, text=True, env=env)
                return result.returncode, result.stdout + result.stderr
            git('init', '-q')
            git('add', '-A')
            git('commit', '-q', '-m', 'first')
            self.assertEqual(0, check()[0], check()[1])
            def word(path):
                path.write_text('a fixtureword here\n')
            cases = [('\u56fe.txt', word, 'forbidden word in \u56fe.txt'), ('odd"name.txt', word, 'cannot be checked'),
                     ('third_party/note.txt', word, 'forbidden word in third_party/note.txt'),
                     ('link', lambda path: path.symlink_to('/nowhere/fixtureword/file'), 'forbidden word in link'),
                     ('big.bin', lambda path: path.write_bytes(bytes(3145729)), 'large file: big.bin')]
            for name, make, message in cases:
                with self.subTest(name=name):
                    (repo / name).parent.mkdir(exist_ok=True)
                    make(repo / name)
                    code, output = check()
                    self.assertNotEqual(0, code, output)
                    self.assertIn(message, output)
                    (repo / name).unlink()
            self.assertEqual(0, check()[0], check()[1])
            git('commit', '-q', '--allow-empty', '-m', 'second', '-m', 'Co-Authored-By: Helper <helper@example.invalid>')
            code, output = check()
            self.assertNotEqual(0, code, output)
            self.assertIn('attribution line in a commit message', output)
            git('reset', '-q', '--hard', 'HEAD~1')
            self.assertEqual(0, check()[0], check()[1])
            git('commit', '-q', '--allow-empty', '-m', 'third', name='Someone Else', mail='someone@example.invalid')
            code, output = check()
            self.assertNotEqual(0, code, output)
            self.assertIn('is not signed as AbsentMotion', output)

    def test_site_tests_are_required_in_ci_and_optional_elsewhere(self):
        with tempfile.TemporaryDirectory() as folder:
            env = {k: v for k, v in os.environ.items() if k != 'CI'}
            env['PATH'] = folder
            command = [sys.executable, str(ROOT / 'tools/site_build.py'), '--check']
            local = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertNotIn('did not run', local.stderr)
            remote = subprocess.run(command, env=dict(env, CI='true'), capture_output=True, text=True)
            self.assertNotEqual(0, remote.returncode)
            self.assertIn('tools/site_test.js did not run', remote.stderr)

    def test_full_check_refuses_native_libraries_older_than_the_native_code(self):
        script = (ROOT / 'tools/check.sh').read_text()
        function = script[script.index('native_libs() {'):script.index('\n}\n', script.index('native_libs() {')) + 3]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            lib = folder / 'android/build/intermediates/merged_native_libs/fossRelease/mergeFossReleaseNativeLibs/out/lib/arm64-v8a/libamengine.so'
            source = folder / 'shared/cpp/engine.cpp'
            run = lambda: subprocess.run(['sh', '-c', 'fail() { echo "FAILED: $1"; }; ' + function + '\nnative_libs fossRelease mergeFossReleaseNativeLibs; echo "rc=$? libs=$libs skipped=$skipped"'],
                                         cwd=folder, capture_output=True, text=True).stdout
            self.assertIn('rc=1', run())
            self.assertIn('not built', run())
            self.assertIn('skipped= fossRelease', run())
            lib.parent.mkdir(parents=True)
            lib.touch()
            source.parent.mkdir(parents=True)
            source.touch()
            os.utime(source, (1000, 1000))
            os.utime(lib, (2000, 2000))
            self.assertIn('rc=0 libs=android/build/intermediates/merged_native_libs/fossRelease/mergeFossReleaseNativeLibs/out/lib/arm64-v8a', run())
            os.utime(source, (3000, 3000))
            stale = run()
            self.assertIn('FAILED: the fossRelease native libraries are older than the native code', stale)
            self.assertIn('rc=1', stale)
            fresh = lib.with_name('liblame_jni.so')
            fresh.touch()
            os.utime(fresh, (4000, 4000))
            mixed = run()
            self.assertIn('FAILED: the fossRelease native libraries are older than the native code', mixed)
            self.assertIn('rc=1', mixed)
            os.utime(lib, (5000, 5000))
            os.utime(fresh, (1000, 1000))
            self.assertIn('rc=0', run())
            ai = lib.with_name('libabsent_ai.so')
            ai.touch()
            os.utime(ai, (2000, 2000))
            dependency = folder / 'third_party/ncnn/src/net.cpp'
            dependency.parent.mkdir(parents=True)
            dependency.touch()
            os.utime(dependency, (3000, 3000))
            self.assertIn('rc=1', run())

    def test_check_waits_for_a_running_build_and_takes_over_from_a_dead_one(self):
        script = (ROOT / 'tools/check.sh').read_text()
        function = script[script.index('lock() {'):script.index('\n}\n', script.index('lock() {')) + 3]
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            def run():
                return subprocess.run(['sh', '-c', 'dir=.; ' + function + '\nlock android; echo "held=$held by $(readlink android.lock)"'],
                                      cwd=folder, capture_output=True, text=True, timeout=60).stdout
            self.assertRegex(run(), r'^held=\./android\.lock by \d+\n$')
            dead = subprocess.Popen(['true'])
            dead.wait()
            (folder / 'android.lock').unlink()
            os.symlink(str(dead.pid), folder / 'android.lock')
            self.assertRegex(run(), r'^held=\./android\.lock by \d+\n$')
            (folder / 'android.lock').unlink()
            if shutil.which('ps'):
                ended = subprocess.Popen(['true'])
                while subprocess.run(['ps', '-o', 'stat=', '-p', str(ended.pid)], capture_output=True, text=True).stdout.strip()[:1] != 'Z':
                    pass
                os.symlink(str(ended.pid), folder / 'android.lock')
                self.assertRegex(run(), r'^held=\./android\.lock by \d+\n$')
                ended.wait()
                (folder / 'android.lock').unlink()
            running = int(subprocess.run(['sh', '-c', 'sleep 4 >/dev/null 2>&1 & echo $!'], capture_output=True, text=True, check=True).stdout)
            os.symlink(str(running), folder / 'android.lock')
            waited = run()
            self.assertIn('waiting for another check.sh (process %d)' % running, waited)
            self.assertIn('held=./android.lock by ', waited)
            self.assertNotIn('by %d\n' % running, waited)

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

    def test_api_floor_refuses_failed_native_symbol_reads(self):
        tree = ast.parse((ROOT / 'tools/api_floor.py').read_text())
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in ('nm', 'undefined')]
        scope = {'subprocess': subprocess}
        exec(compile(ast.Module(body=functions, type_ignores=[]), 'api_floor.py', 'exec'), scope)
        with tempfile.TemporaryDirectory() as folder:
            tool = Path(folder) / 'nm'
            tool.write_text('#!/bin/sh\necho "invalid library" >&2\nexit 1\n')
            tool.chmod(0o755)
            for function, args in [('nm', [tool, ['--defined-only'], Path('invalid.so')]), ('undefined', [tool, Path('invalid.so')])]:
                with self.assertRaises(subprocess.CalledProcessError):
                    scope[function](*args)

    def test_apk_packaging_rejects_compression_and_alignment_mismatches(self):
        spec = importlib.util.spec_from_file_location('apk_check', ROOT / 'tools/apk_check.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        library = bytearray(128)
        library[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', library, 18, 183)
        struct.pack_into('<Q', library, 32, 64)
        struct.pack_into('<HH', library, 54, 56, 1)
        struct.pack_into('<I', library, 64, 1)
        struct.pack_into('<Q', library, 112, 16384)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            for compression, aligned in [(zipfile.ZIP_STORED, True), (zipfile.ZIP_STORED, False), (zipfile.ZIP_DEFLATED, False)]:
                with self.subTest(compression=compression, aligned=aligned):
                    with zipfile.ZipFile(path, 'w') as archive:
                        archive.writestr('resources.arsc', b'res!')
                        entry = zipfile.ZipInfo('lib/arm64-v8a/libfixture.so')
                        entry.compress_type = compression
                        if aligned:
                            padding = (-archive.fp.tell() - 30 - len(entry.filename)) % 16384
                            entry.extra = struct.pack('<HH', 0xffff, padding - 4) + bytes(padding - 4)
                        archive.writestr(entry, library)
                    self.assertEqual((1, 'arm64-v8a'), module.zip_packaging(path, True))
                    if aligned:
                        self.assertEqual((1, 'arm64-v8a'), module.zip_packaging(path, False))
                    else:
                        with self.assertRaisesRegex(ValueError, 'direct loading'):
                            module.zip_packaging(path, False)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('resources.arsc', b'res!', compress_type=zipfile.ZIP_DEFLATED)
                archive.writestr('lib/arm64-v8a/libfixture.so', library)
            with self.assertRaisesRegex(ValueError, 'resources.arsc'):
                module.zip_packaging(path, True)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('resources.arsc', b'res!')
                archive.writestr('lib/arm64-v8a/libfixture.so', library)
            damaged = bytearray(path.read_bytes())
            damaged[damaged.index(b'res!')] ^= 1
            path.write_bytes(damaged)
            with self.assertRaises((ValueError, zipfile.BadZipFile)):
                module.zip_packaging(path, True)
            narrow = bytearray(96)
            narrow[:6] = b'\x7fELF\x01\x01'
            struct.pack_into('<H', narrow, 18, 40)
            struct.pack_into('<I', narrow, 28, 52)
            struct.pack_into('<HH', narrow, 42, 32, 1)
            struct.pack_into('<I', narrow, 52, 1)
            struct.pack_into('<I', narrow, 80, 4096)
            self.assertEqual(('armeabi-v7a', 4096), module.elf_alignment(narrow, 'narrow.so'))
            intel = bytearray(library)
            struct.pack_into('<H', intel, 18, 62)
            self.assertEqual(('x86_64', 16384), module.elf_alignment(intel, 'intel.so'))
            struct.pack_into('<H', intel, 18, 3)
            with self.assertRaisesRegex(ValueError, 'expected an arm64'):
                module.elf_alignment(intel, 'i386.so')
            for names, error in [(['lib/armeabi-v7a/libfixture.so'], None), (['lib/arm64-v8a/libfixture.so'], 'unexpected native ABI'), (['lib/armeabi-v7a/libfixture.so', 'lib/arm64-v8a/libother.so'], 'one native ABI')]:
                with self.subTest(names=names):
                    with zipfile.ZipFile(path, 'w') as archive:
                        archive.writestr('resources.arsc', b'res!')
                        archive.writestr(names[0], narrow)
                        for name in names[1:]:
                            archive.writestr(name, library)
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            module.zip_packaging(path, True)
                    else:
                        self.assertEqual((1, 'armeabi-v7a'), module.zip_packaging(path, True))
            struct.pack_into('<I', narrow, 80, 1024)
            with self.assertRaisesRegex(ValueError, '4 KB'):
                module.elf_alignment(narrow, 'narrow.so')
        with tempfile.TemporaryDirectory() as folder:
            libs = Path(folder)
            with self.assertRaisesRegex(ValueError, 'no native libraries'):
                module.library_alignment([libs])
            (libs / 'libgood.so').write_bytes(library)
            (libs / 'notes.txt').write_text('not a library')
            self.assertEqual((1, '16 KB'), module.library_alignment([libs]))
            self.assertEqual((2, '16 KB'), module.library_alignment([libs, libs / 'libgood.so']))
            struct.pack_into('<Q', library, 112, 4096)
            (libs / 'libold.so').write_bytes(library)
            with self.assertRaisesRegex(ValueError, 'libold.so: ELF load segment is not aligned for 16 KB pages'):
                module.library_alignment([libs])
            result = subprocess.run([sys.executable, str(ROOT / 'tools/apk_check.py'), '--libraries', str(libs)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('libold.so', result.stderr)
            (libs / 'libold.so').unlink()
            result = subprocess.run([sys.executable, str(ROOT / 'tools/apk_check.py'), '--libraries', str(libs)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('1 native libraries are aligned for 16 KB memory pages', result.stdout)
        with self.assertRaisesRegex(ValueError, '16 KB'):
            module.elf_alignment(library, 'fixture.so')
        struct.pack_into('<Q', library, 112, 16384)
        struct.pack_into('<Q', library, 72, 4096)
        with self.assertRaisesRegex(ValueError, '16 KB'):
            module.elf_alignment(library, 'shifted.so')
        with self.assertRaises(ValueError):
            module.elf_alignment(library[:64], 'truncated.so')

    def test_apk_assets_need_language_packs_and_slow_pictures_in_compatible(self):
        spec = importlib.util.spec_from_file_location('apk_check', ROOT / 'tools/apk_check.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        def picture(*times):
            frames = b''.join(b'ANMF' + struct.pack('<I', 17) + bytes(12) + time.to_bytes(3, 'little') + bytes(3) for time in times)
            body = b'WEBP' + b'VP8X' + struct.pack('<I', 10) + bytes([2]) + bytes(9) + b'ANIM' + struct.pack('<I', 6) + bytes(6) + frames
            return b'RIFF' + struct.pack('<I', len(body)) + body
        self.assertEqual([83, 166], module.frame_times(picture(83, 166)))
        self.assertEqual([], module.frame_times(b'RIFF' + struct.pack('<I', 16) + b'WEBPVP8 ' + struct.pack('<I', 4) + b'ANMF'))
        good = {'assets/effects/index.json': b'[]', 'assets/langpacks/de.pack': b'pack', 'assets/effects/thumb/still.webp': b'RIFF',
                'assets/effects/thumb/moving.webp': picture(83, 166, 83)}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.apk'
            def packed(files):
                with zipfile.ZipFile(path, 'w') as archive:
                    for name, data in files.items():
                        archive.writestr(name, data)
                return path
            self.assertEqual(1, module.asset_packaging(packed(good), True))
            fast = dict(good, **{'assets/effects/thumb/moving.webp': picture(99, 33, 66)})
            self.assertEqual(1, module.asset_packaging(packed(fast), False))
            with self.assertRaisesRegex(ValueError, 'faster than 12 frames'):
                module.asset_packaging(packed(fast), True)
            for extra in ['assets/help/de/index.html', 'assets/effects/i18n/de.json']:
                with self.assertRaisesRegex(ValueError, 'packed twice'):
                    module.asset_packaging(packed(dict(good, **{extra: b'x'})), False)
            for gone, message in [('assets/langpacks/de.pack', 'language packs are missing'), ('assets/effects/index.json', 'language packs are missing'),
                                  ('assets/effects/thumb/moving.webp', None), ('assets/effects/thumb/still.webp', None)]:
                files = {name: data for name, data in good.items() if name != gone}
                if message:
                    with self.assertRaisesRegex(ValueError, message):
                        module.asset_packaging(packed(files), False)
                else:
                    self.assertEqual(1, module.asset_packaging(packed(files), True))
            with self.assertRaisesRegex(ValueError, 'effect pictures are missing'):
                module.asset_packaging(packed({name: data for name, data in good.items() if 'thumb' not in name}), False)

    def test_publish_scan_reads_library_data_but_not_machine_code(self):
        def library(code, data, sections=True):
            elf = bytearray(248)
            elf[:6] = b'\x7fELF\x01\x01'
            elf[64:64 + len(code)] = code
            elf[96:96 + len(data)] = data
            if sections:
                struct.pack_into('<I', elf, 0x20, 128)
                struct.pack_into('<HH', elf, 0x2E, 40, 3)
                struct.pack_into('<IIIIII', elf, 168, 0, 1, 6, 0, 64, 32)
                struct.pack_into('<IIIIII', elf, 208, 0, 1, 2, 0, 96, 32)
            return bytes(elf)
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / 'words.txt').write_text('fixtureword\n')
            cases = {'code.so': (library(b'FIXTUREWORD', b''), False), 'data.so': (library(b'', b'fixtureword'), True),
                     'bare.so': (library(b'fixtureword', b'', sections=False), True)}
            for name, (data, found) in cases.items():
                (folder / name).write_bytes(data)
                result = subprocess.run([sys.executable, str(ROOT / 'tools/publish_scan.py'), str(folder / 'words.txt'), str(folder / 'none.txt'), ''],
                                        input=str(folder / name), capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(found, 'PROBLEM' in result.stdout, name)

    def test_release_key_pin_is_read_from_the_app_and_must_be_unique(self):
        spec = importlib.util.spec_from_file_location('apk_check', ROOT / 'tools/apk_check.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertRegex(module.release_signer(), r'^[0-9a-f]{64}$')
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'UpdateSource.kt'
            line = 'internal const val RELEASE_SIGNER = "%s"\n'
            for text in ['', line % ('a' * 63), line % ('a' * 64) + line % ('b' * 64), line % ('A' * 64)]:
                source.write_text(text)
                with self.assertRaisesRegex(ValueError, 'RELEASE_SIGNER'):
                    module.release_signer(source)
            source.write_text('package x\n' + line % ('c' * 64))
            self.assertEqual('c' * 64, module.release_signer(source))

    def test_help_pictures_land_at_the_same_block_in_every_language_and_can_be_replaced(self):
        spec = importlib.util.spec_from_file_location('help_shots', ROOT / 'tools/help_shots.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        english = '\n\n'.join(['Intro.', '## One', 'First.', '- a\n- b', '## Two', 'Last.']) + '\n'
        translated = english.replace('Intro.', 'Texto.').replace('First.', 'Primero.')
        shot = {'id': 'demo', 'alt': {'en': 'English (1).', 'es': 'Espanol (1).'}}
        place = {'section': 'One', 'block': 2}
        for text, tag in ((english, 'en'), (translated, 'es')):
            body = module.place_in(english, text, 'page', tag, [(shot, place)], ['demo'])
            self.assertIn('- a\n- b\n\n![%s](img/demo.webp)\n\n## Two' % shot['alt'][tag], body)
            self.assertEqual(body, module.place_in(english, body, 'page', tag, [(shot, place)], ['demo']))
            self.assertEqual(text, module.strip_pictures(body, ['demo']))
        with self.assertRaises(module.Failure):
            module.place_in(english, translated.replace('- a\n- b', 'x'), 'page', 'es', [(shot, place)], ['demo'])
        from PIL import Image
        plain = Image.new('RGB', (200, 200), (200, 200, 200))
        marked = module.annotate(plain.copy(), [((20, 20, 100, 100), 12, True)], [((40, 40), 1)], module.STYLE)
        self.assertEqual(plain.size, marked.size)
        self.assertNotEqual(plain.tobytes(), marked.tobytes())
        tile = {'box': (20, 20, 100, 100), 'parent': -1, 'clickable': True}
        self.assertEqual(tile['box'], module.hit_box([tile], {'box': (30, 30, 50, 50), 'parent': 0}, False))
        self.assertEqual((30, 30, 50, 50), module.hit_box([tile], {'box': (30, 30, 50, 50), 'parent': 0}, True))

if __name__ == '__main__':
    unittest.main()
