import importlib.machinery
import importlib.util
import os
import sys

skia = os.path.abspath(sys.argv[1])
skip = {'angle2', 'dawn', 'swiftshader', 'emsdk', 'd3d12allocator', 'vello', 'perfetto', 'partition_alloc',
        'libjxl', 'highway', 'libavif', 'libgav1', 'imgui', 'unicodetools', 'icu4x', 'oboe', 'vulkan-deps',
        'vulkan-tools', 'jinja2', 'markupsafe', 'egl-registry', 'opengl-registry', 'abseil-cpp'}
scope = {}
with open(os.path.join(skia, "DEPS")) as f:
    exec('def Var(x): return vars[x]\n' + f.read(), scope)
deps = {k: v for k, v in scope['deps'].items() if isinstance(v, str) and k.split('/')[-1] not in skip}
filtered = os.path.join(skia, "DEPS.filtered")
with open(filtered, "w") as f:
    f.write('deps = ' + repr(deps) + '\n')

loader = importlib.machinery.SourceFileLoader("git_sync_deps", os.path.join(skia, "tools", "git-sync-deps"))
spec = importlib.util.spec_from_loader("git_sync_deps", loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)
module.git_sync_deps(filtered, [], True, True)
