$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$buildDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
$expectedBuildDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
if ($buildDirectory -ne $expectedBuildDirectory -or -not $buildDirectory.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar)) {
    throw "Refusing to clean unexpected build directory: $buildDirectory"
}
if (Test-Path -LiteralPath $buildDirectory) {
    Remove-Item -LiteralPath $buildDirectory -Recurse -Force
}

$mainClasses = Join-Path $buildDirectory 'classes-main'
$testClasses = Join-Path $buildDirectory 'classes-test'
$distDirectory = Join-Path $buildDirectory 'dist'
New-Item -ItemType Directory -Path $mainClasses, $testClasses, $distDirectory -Force | Out-Null

$mainSources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' |
    Sort-Object FullName |
    ForEach-Object FullName
$testSources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\test\java') -Recurse -File -Filter '*.java' |
    Sort-Object FullName |
    ForEach-Object FullName
$compileOnlySources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\compileOnly\java') -Recurse -File -Filter '*.java' |
    Sort-Object FullName |
    ForEach-Object FullName

if (-not $mainSources) {
    throw 'No main Java sources found.'
}

Write-Output '[1/8] Compiling Java 21-compatible main classes with Fabric compile-only API shape...'
$mainArguments = @('--release', '21', '-encoding', 'UTF-8', '-d', $mainClasses) + $compileOnlySources + $mainSources
& javac @mainArguments
if ($LASTEXITCODE -ne 0) {
    throw "javac main failed with exit code $LASTEXITCODE"
}

Write-Output '[2/8] Compiling tests...'
$testArguments = @('--release', '21', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-d', $testClasses) + $compileOnlySources + $mainSources + $testSources
& javac @testArguments
if ($LASTEXITCODE -ne 0) {
    throw "javac tests failed with exit code $LASTEXITCODE"
}

Write-Output '[3/8] Running tests...'
& java --add-modules jdk.httpserver -cp "$mainClasses;$testClasses" io.github.mcmodsync.AllTests
if ($LASTEXITCODE -ne 0) {
    throw "tests failed with exit code $LASTEXITCODE"
}

$jarPath = Join-Path $distDirectory 'MCModSync-1.7.0.jar'
$compileOnlyStubClass = Join-Path $mainClasses 'net\fabricmc\loader\api\entrypoint\PreLaunchEntrypoint.class'
if (-not (Test-Path -LiteralPath $compileOnlyStubClass -PathType Leaf)) {
    throw "Expected compile-only Fabric API class not found: $compileOnlyStubClass"
}
Remove-Item -LiteralPath $compileOnlyStubClass -Force
$fabricStubRoot = Join-Path $mainClasses 'net\fabricmc'
if (Test-Path -LiteralPath $fabricStubRoot) {
    Remove-Item -LiteralPath $fabricStubRoot -Recurse -Force
}

Write-Output '[4/8] Building Fabric/executable/agent JAR...'
& jar --create --file $jarPath --manifest (Join-Path $projectRoot 'manifest.mf') `
    -C $mainClasses . `
    -C (Join-Path $projectRoot 'src\main\resources') .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}
$fabricLeak = & jar tf $jarPath | Select-String -Pattern '^net/fabricmc/'
if ($fabricLeak) {
    throw "Refusing to ship Fabric Loader API classes inside MCModSync jar: $fabricLeak"
}


Write-Output '[5/8] Verifying the real portable helper exits cleanly and installs the update...'
& java --add-modules jdk.httpserver -cp "$jarPath;$testClasses" `
    io.github.mcmodsync.PostBuildPortableSmoke $jarPath $testClasses
if ($LASTEXITCODE -ne 0) {
    throw "post-build portable helper smoke test failed with exit code $LASTEXITCODE"
}

Write-Output '[6/8] Verifying legacy fail-open configuration can no longer bypass blocking...'
$smokeDirectory = Join-Path $buildDirectory 'agent-smoke-game'
New-Item -ItemType Directory -Path (Join-Path $smokeDirectory 'mods') -Force | Out-Null
$agentArgument = "-javaagent:$jarPath=gameDir=$smokeDirectory;manifest=http://127.0.0.1:1/mods.txt;requireManifest=false;connectTimeoutSeconds=1"
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$smokeOutput = & java '-Dmodsync.disableDialogs=true' '-Dmodsync.syncResourcePacks=false' '-Dmodsync.syncServerList=false' $agentArgument -cp "$mainClasses;$testClasses" io.github.mcmodsync.DummyMain 2>&1
$smokeExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorAction
$smokeText = $smokeOutput | Out-String
if ($smokeExitCode -ne 0) {
    throw 'legacy fail-open rejection did not exit normally'
}
if ($smokeText -notmatch 'STARTUP_BLOCKED') {
    throw 'legacy fail-open rejection did not emit STARTUP_BLOCKED'
}
if ($smokeText -match 'Dummy main reached') {
    throw 'legacy requireManifest=false unexpectedly reached the game main class'
}
Write-Output 'Legacy fail-open bypass rejection and normal exit passed.'

Write-Output '[7/8] Verifying fatal errors really block startup...'
$fatalDirectory = Join-Path $buildDirectory 'agent-fatal-game'
New-Item -ItemType Directory -Path (Join-Path $fatalDirectory 'mods') -Force | Out-Null
$fatalAgentArgument = "-javaagent:$jarPath=gameDir=$fatalDirectory;manifest=http://127.0.0.1:1/mods.txt;requireManifest=true;connectTimeoutSeconds=1"
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$fatalOutput = & java '-Dmodsync.disableDialogs=true' '-Dmodsync.syncResourcePacks=false' '-Dmodsync.syncServerList=false' $fatalAgentArgument -cp "$mainClasses;$testClasses" io.github.mcmodsync.DummyMain 2>&1
$fatalExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorAction
$fatalText = $fatalOutput | Out-String
if ($fatalExitCode -ne 0) {
    throw 'fatal javaagent test did not exit normally'
}
if ($fatalText -notmatch 'STARTUP_BLOCKED') {
    throw 'fatal javaagent test did not emit STARTUP_BLOCKED'
}
if ($fatalText -match 'Dummy main reached') {
    throw 'fatal javaagent test unexpectedly reached the game main class'
}
Write-Output 'Fatal startup-block normal-exit test passed.'

Write-Output '[8/8] Copying deliverables...'
$workspaceRoot = [System.IO.Directory]::GetParent([System.IO.Directory]::GetParent($projectRoot).FullName).FullName
$outputsDirectory = Join-Path $workspaceRoot 'outputs'
New-Item -ItemType Directory -Path $outputsDirectory -Force | Out-Null
$jarOutputName = 'MCModSync-1.7.0.jar'
Get-ChildItem -LiteralPath $outputsDirectory -File -Filter 'MCModSync-*.jar' -ErrorAction SilentlyContinue |
    Where-Object Name -ne $jarOutputName |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $outputsDirectory $jarOutputName) -Force
$readmeDestinationName = 'MCModSync-README-zh-CN.md'
Get-ChildItem -LiteralPath $outputsDirectory -File -Filter 'MCModSync-*.md' -ErrorAction SilentlyContinue |
    Where-Object Name -ne $readmeDestinationName |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $outputsDirectory $readmeDestinationName) -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'modsync.properties.example') -Destination (Join-Path $outputsDirectory 'modsync.properties.example') -Force

$sourceZip = Join-Path $outputsDirectory 'MCModSync-1.7.0-source.zip'
Get-ChildItem -LiteralPath $outputsDirectory -File -Filter 'MCModSync-*-source.zip' -ErrorAction SilentlyContinue |
    Where-Object FullName -ne $sourceZip |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath $sourceZip) {
    Remove-Item -LiteralPath $sourceZip -Force
}
Compress-Archive -Path @(
    (Join-Path $projectRoot 'src'),
    (Join-Path $projectRoot 'build.ps1'),
    (Join-Path $projectRoot 'manifest.mf'),
    (Join-Path $projectRoot 'README.md'),
    (Join-Path $projectRoot 'modsync.properties.example'),
    (Join-Path $projectRoot 'LICENSE'),
    (Join-Path $projectRoot 'docs')
) -DestinationPath $sourceZip -CompressionLevel Optimal

Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputsDirectory $jarOutputName) |
    Select-Object Algorithm, Hash, Path
Write-Output "Build complete: $outputsDirectory"
