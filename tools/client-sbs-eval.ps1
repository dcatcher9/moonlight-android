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

# Each class must exist in a JVM test source set. Gradle accepts a --tests filter that
# matches nothing as long as another filter matches, so a stale name would report green
# for a test that never ran.
$focusedTests = @(
    "com.limelight.sbs.ClientSbsFrameSlotsTest",
    "com.limelight.sbs.ClientSbsComputeProgramConsistencyTest",
    "com.limelight.sbs.ClientSbsGpuDepthShadersTest",
    "com.limelight.sbs.ClientSbsGpuDisparityShadersTest",
    "com.limelight.sbs.ClientSbsGpuNearReuseClassifierTest",
    "com.limelight.sbs.ClientSbsGpuNearReuseShadersTest",
    "com.limelight.sbs.ClientSbsNearIdenticalPolicyTest",
    "com.limelight.sbs.ClientSbsGpuTimerTest",
    "com.limelight.binding.video.DecodedVideoDimensionsTest",
    "com.limelight.binding.video.DecoderModeTransitionGateTest",
    "com.limelight.binding.video.MediaCodecDecoderRendererTelemetryTest",
    "com.limelight.binding.video.MediaCodecDecoderRendererTransitionTest",
    "com.limelight.binding.video.MediaCodecHelperRegularDecoderTest",
    "com.limelight.preferences.PreferenceConfigurationClientSbsModelMigrationTest",
    "com.limelight.utils.ClientSbsGpuInferenceEngineTest",
    "com.limelight.utils.ClientSbsModelArchiveTest",
    "com.limelight.utils.ClientSbsPackagedModelArchiveTest",
    "com.limelight.utils.ClientSbsOutputSurfaceValidationTest",
    "com.limelight.utils.ClientSbsPerformanceSnapshotTest",
    "com.limelight.utils.ClientSbsSwapProofTest",
    "com.limelight.utils.ClientSbsModelManifestTest",
    "com.limelight.utils.Stereo3DRendererSchedulingTest",
    "com.limelight.utils.ShaderUtilsTest",
    "com.limelight.ui.ClientSbsResizePolicyTest",
    "com.limelight.ui.XrStreamPresenterLayoutTest",
    "com.limelight.ui.XrStreamPresenterTransitionTest",
    "com.limelight.ui.XrStreamPresenterVideoModeAckTest",
    "com.limelight.ui.XrViewStateStoreTest"
)

$testSourceRoots = @(
    (Join-Path $repoRoot "app\src\test\java"),
    (Join-Path $repoRoot "app\src\testNonRoot_game\java")
)
foreach ($testClass in $focusedTests) {
    $relativePath = ($testClass -replace "\.", "\") + ".java"
    $found = $false
    foreach ($root in $testSourceRoots) {
        if (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf) {
            $found = $true
        }
    }
    if (-not $found) {
        throw "Focused test class $testClass has no source in app\src\test or app\src\testNonRoot_game"
    }
}

$gradleArgs = @(":app:testNonRoot_gameDebugUnitTest")
foreach ($testClass in $focusedTests) {
    $gradleArgs += "--tests"
    $gradleArgs += $testClass
}
$gradleArgs += "--console=plain"

Push-Location $repoRoot
try {
    Write-Host "Running focused native-GPU Client SBS JVM tests (no device changes)..."
    & $gradleWrapper @gradleArgs
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
