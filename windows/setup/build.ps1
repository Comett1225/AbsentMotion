param([string]$App = '', [string]$Version = '', [string]$Out = '', [switch]$Xpress, [switch]$StubOnly, [string]$UpgradeCode = '{5B0E7A52-3C1F-4D8E-9A6B-2F4C8D1E7A30}', [string]$DefaultName = 'AbsentMotion', [string]$Build = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Version) { $Version = ((Get-Content (Join-Path $root 'version.properties')) | Where-Object { $_ -like 'versionName=*' } | Select-Object -First 1).Split('=')[1].Trim() }
if (-not $App) { $App = Join-Path $root 'pc/build/compose/binaries/main/app/AbsentMotion' }
if (-not $Out) { $Out = Join-Path $root 'pc/build/compose/binaries/main/setup' }
if (-not $Build) { $Build = Join-Path $root 'pc/build/setup-win' }
if (-not $StubOnly -and -not (Test-Path (Join-Path $App 'AbsentMotion.exe'))) { throw "No app image in $App; run gradlew createDistributable first" }
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ Build Tools were not found' }
$env:PATH = (Split-Path -Parent $vswhere) + ';' + $env:PATH
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$clang = Join-Path $env:ProgramFiles 'LLVM/bin/clang-cl.exe'
cmake -S $PSScriptRoot -B $Build -G Ninja "-DCMAKE_CXX_COMPILER=$clang" -DCMAKE_BUILD_TYPE=Release "-DAM_VERSION=$Version" "-DAM_UPGRADE_CODE=$UpgradeCode" "-DAM_DEFAULT_NAME=$DefaultName"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build $Build --parallel 6
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force $Out | Out-Null
Copy-Item (Join-Path $Build 'AbsentMotionSetup.exe') (Join-Path $Out 'AbsentMotionSetup.exe') -Force
if ($StubOnly) { exit 0 }
$exe = Join-Path $Out "AbsentMotion-$Version-windows-x64-setup.exe"
$extra = @()
if ($Xpress) { $extra += '--xpress' }
& (Join-Path $Build 'ampack.exe') $App $Version (Join-Path $Build 'AbsentMotionSetup.exe') $exe @extra
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$pack = Join-Path $Out "AbsentMotion-$Version-windows-x64.ampack"
& (Join-Path $Build 'ampack.exe') $App $Version (Join-Path $Build 'AbsentMotionSetup.exe') $pack @extra --payload-only
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
exit 0
