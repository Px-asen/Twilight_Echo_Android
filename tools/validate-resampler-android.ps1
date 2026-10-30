param(
    [string]$AndroidRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$SharedRoot = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'Twilight_Echo'),
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [string]$NdkVersion = '27.0.12077973',
    [ValidateSet('x86_64','x86','arm64-v8a','armeabi-v7a')][string]$Abi = 'x86_64',
    [string]$Device = 'emulator-5554',
    [switch]$DisableDirectSoxr
)
$ErrorActionPreference = 'Stop'
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed: $LASTEXITCODE" }
}
if (-not $Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
$ff = Join-Path $AndroidRoot ".gradle/ffmpeg/7.1.2/$Abi"
$variant = if ($DisableDirectSoxr) { 'no-soxr' } else { 'soxr' }
$build = Join-Path $SharedRoot "audio-engine/build/android-resampler-$Abi-$variant"
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$libs = @('avformat','avcodec','swresample','avutil') | ForEach-Object { "$ff/lib/lib$_.so" }
$enabled = if ($DisableDirectSoxr) { 'OFF' } else { 'ON' }
Invoke-Checked $CMake @('-S', "$SharedRoot/audio-engine", '-B', $build, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$Sdk/ndk/$NdkVersion/build/cmake/android.toolchain.cmake",
    "-DANDROID_ABI=$Abi", '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release', '-DFFMPEG_FOUND=TRUE', "-DFFMPEG_INCLUDE_DIRS=$ff/include",
    "-DFFMPEG_LIBRARIES=$($libs -join ';')", "-DTAE_ENABLE_SOXR=$enabled")
Invoke-Checked $CMake @('--build', $build, '--target', 'twilight_resampler_response_tests',
    'twilight_usb_pcm_decoder_tests', '-j', '2')
$remote = "/data/local/tmp/twilight-resampler-$Abi-$variant"
Invoke-Checked $adb @('-s', $Device, 'shell', "mkdir -p $remote")
$files = @("$build/twilight_resampler_response_tests", "$build/twilight_usb_pcm_decoder_tests") + $libs
if (-not $DisableDirectSoxr) { $files += "$build/libtwilight-soxr.so" }
foreach ($file in $files) { Invoke-Checked $adb @('-s', $Device, 'push', $file, "$remote/") }
$outputDirectory = Join-Path $AndroidRoot 'build'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
foreach ($suite in @('resampler_response','usb_pcm_decoder')) {
    $binary = "twilight_${suite}_tests"
    $output = Join-Path $outputDirectory "minimum-android-$Abi-$variant-$suite.jsonl"
    $stderr = "$output.stderr.log"
    Invoke-Checked $adb @('-s', $Device, 'shell',
        "cd $remote && chmod 755 $binary && LD_LIBRARY_PATH=. ./$binary") 2> $stderr |
        Set-Content -LiteralPath $output -Encoding utf8
    Write-Output "Measurements: $output"
}
