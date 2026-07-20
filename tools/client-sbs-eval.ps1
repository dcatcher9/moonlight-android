[CmdletBinding()]
param(
    [switch]$Assemble
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$defaultAndroidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"

if (-not $env:ANDROID_HOME) {
    if ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
    }
    elseif (Test-Path -LiteralPath $defaultAndroidSdk -PathType Container) {
        $env:ANDROID_HOME = $defaultAndroidSdk
    }
}

if (-not $env:ANDROID_HOME) {
    throw "Set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK"
}

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper not found at $gradleWrapper"
}

Push-Location $repoRoot
try {
    Write-Host "Running four focused native-GPU Client SBS JVM tests (no device changes)..."
    & $gradleWrapper `
        ":app:testNonRoot_gameDebugUnitTest" `
        "--tests" "com.limelight.sbs.ClientSbsFrameSlotsTest" `
        "--tests" "com.limelight.sbs.ClientSbsGpuDepthShadersTest" `
        "--tests" "com.limelight.utils.ClientSbsModelManifestTest" `
        "--tests" "com.limelight.utils.ShaderUtilsTest" `
        "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "Client SBS JVM tests failed with exit code $LASTEXITCODE"
    }

    if ($Assemble) {
        Write-Host "Assembling nonRoot_gameDebug..."
        & $gradleWrapper ":app:assembleNonRoot_gameDebug" "--console=plain"
        if ($LASTEXITCODE -ne 0) {
            throw "Client SBS debug assemble failed with exit code $LASTEXITCODE"
        }
    }
}
finally {
    Pop-Location
}
