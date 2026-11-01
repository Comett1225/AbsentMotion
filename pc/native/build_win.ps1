$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21' }
if (-not $env:SKIA_DIR) { $env:SKIA_DIR = 'C:/am/skia' }
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ Build Tools were not found' }
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$clang = Join-Path $env:ProgramFiles 'LLVM/bin/clang-cl.exe'
$root = Split-Path -Parent $PSScriptRoot
cmake -S $PSScriptRoot -B "$root/build/native-win" -G Ninja "-DCMAKE_CXX_COMPILER=$clang" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build "$root/build/native-win" --parallel 6
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item (Join-Path $env:SKIA_DIR 'out/win-x64/icudtl.dat') "$root/build/native/icudtl.dat" -Force
exit 0
