param([string]$App = '', [string]$Version = '', [string]$Out = '', [switch]$Xpress)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Version) { $Version = ((Get-Content (Join-Path $root 'version.properties')) | Where-Object { $_ -like 'versionName=*' } | Select-Object -First 1).Split('=')[1].Trim() }
if (-not $App) { $App = Join-Path $root 'pc/build/compose/binaries/main/app/AbsentMotion' }
if (-not $Out) { $Out = Join-Path $root 'pc/build/compose/binaries/main/setup' }
if (-not (Test-Path (Join-Path $App 'AbsentMotion.exe'))) { throw "No app image in $App; run gradlew createDistributable first" }
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ Build Tools were not found' }
$env:PATH = (Split-Path -Parent $vswhere) + ';' + $env:PATH
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$clang = Join-Path $env:ProgramFiles 'LLVM/bin/clang-cl.exe'
$build = Join-Path $root 'pc/build/setup-win'
cmake -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_CXX_COMPILER=$clang" -DCMAKE_BUILD_TYPE=Release "-DAM_VERSION=$Version"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build $build --parallel 6
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force $Out | Out-Null
$exe = Join-Path $Out "AbsentMotion-$Version-windows-x64-setup.exe"
$extra = @()
if ($Xpress) { $extra += '--xpress' }
& (Join-Path $build 'ampack.exe') $App $Version (Join-Path $build 'AbsentMotionSetup.exe') $exe @extra
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$pack = Join-Path $Out "AbsentMotion-$Version-windows-x64.ampack"
& (Join-Path $build 'ampack.exe') $App $Version (Join-Path $build 'AbsentMotionSetup.exe') $pack @extra --payload-only
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item (Join-Path $build 'AbsentMotionSetup.exe') (Join-Path $Out 'AbsentMotionSetup.exe') -Force
exit 0
