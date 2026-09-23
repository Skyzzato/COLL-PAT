param([switch]$Instrumented, [switch]$Full)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidRoot = Join-Path $projectRoot 'android'
$buildRoot = Join-Path $androidRoot 'app\build-v016'
# Normalize only generated build attributes. Never touch application data or other projects.
if (Test-Path -LiteralPath $buildRoot) {
    $buildItems = @(Get-Item -LiteralPath $buildRoot) + @(Get-ChildItem -LiteralPath $buildRoot -Recurse -Force)
    foreach ($item in $buildItems) {
        if (-not $item.FullName.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Percorso esterno alla build' }
        $item.Attributes = $item.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
    }
}
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'C:\Program Files\Android\Android Studio\jbr')) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
Push-Location -LiteralPath $androidRoot
try {
    $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:assembleDebugAndroidTest')
    if ($Full) { $tasks = @('clean', 'build', ':app:assembleDebugAndroidTest') }
    if ($Instrumented) { $tasks += ':app:connectedDebugAndroidTest' }
    & '.\gradlew.bat' @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build non riuscita: $LASTEXITCODE" }
} finally { Pop-Location }
