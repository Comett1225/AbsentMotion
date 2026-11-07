$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 21' }
if (-not (Select-String -Path (Join-Path $env:JAVA_HOME 'release') -Pattern '^IMPLEMENTOR="JetBrains' -Quiet -ErrorAction SilentlyContinue)) { Write-Warning 'JAVA_HOME is not the JetBrains Runtime 21, so the app falls back to the system title bar' }
if (-not $env:SKIA_DIR) { throw 'SKIA_DIR must point to Skia built for Windows (out/win-x64)' }
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ Build Tools were not found' }
$env:PATH = (Split-Path -Parent $vswhere) + ';' + $env:PATH
$vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
cmd /c "`"$vcvars`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$clang = Join-Path $env:ProgramFiles 'LLVM/bin/clang-cl.exe'
$root = Split-Path -Parent $PSScriptRoot
cmake -S "$root/pc/native" -B "$root/pc/build/native-win" -G Ninja "-DCMAKE_C_COMPILER=$clang" "-DCMAKE_CXX_COMPILER=$clang" -DCMAKE_BUILD_TYPE=Release "-DFFMPEG_DIR=$env:FFMPEG_DIR"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build "$root/pc/build/native-win" --parallel 6
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$native = "$root/pc/build/native"
function Place($from, $name) {
    $to = Join-Path $native $name
    $old = Get-Item $to -ErrorAction SilentlyContinue
    if (-not $old -or $old.Length -ne $from.Length -or $old.LastWriteTimeUtc -ne $from.LastWriteTimeUtc) { Copy-Item $from.FullName $to -Force }
}
Place (Get-Item (Join-Path $env:SKIA_DIR 'out/win-x64/icudtl.dat')) 'icudtl.dat'
$ffmpeg = '^(av|sw)\w+-\d+\.dll$|^ff(mpeg|probe)\.exe$'
$wanted = @{}
$ffbin = if ($env:FFMPEG_DIR) { Join-Path $env:FFMPEG_DIR 'bin' } else { $null }
if ($ffbin -and (Test-Path (Join-Path $env:FFMPEG_DIR 'include/libavcodec/avcodec.h'))) {
    Get-ChildItem $ffbin -File | Where-Object { $_.Name -match $ffmpeg } | ForEach-Object { Place $_ $_.Name; $wanted[$_.Name] = $true }
    $license = Get-Item (Join-Path $env:FFMPEG_DIR 'LICENSE.txt') -ErrorAction SilentlyContinue
    if ($license) { Place $license 'ffmpeg-LICENSE.txt'; $wanted['ffmpeg-LICENSE.txt'] = $true }
}
Get-ChildItem $native -File | Where-Object { ($_.Name -match $ffmpeg -or $_.Name -eq 'ffmpeg-LICENSE.txt') -and -not $wanted[$_.Name] } | Remove-Item -Force
exit 0
