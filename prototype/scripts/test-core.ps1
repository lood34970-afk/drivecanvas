$ErrorActionPreference = 'Stop'

# Gradle's test worker cannot start on this machine (non-ASCII user home), so compile with
# Gradle and run JUnit directly, like the root project's scripts/test-score-engine.ps1.
$prototypeRoot = Split-Path -Parent $PSScriptRoot
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Java\temurin-17.0.20.1+1' }
$java = Join-Path $javaHome 'bin\java.exe'
$moduleCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-DependencyJar {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Filter
    )

    $jar = Get-ChildItem -LiteralPath $Root -Filter $Filter -Recurse |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Dependency not found: $Filter" }
    return $jar
}

$env:JAVA_HOME = $javaHome
Push-Location $prototypeRoot
try {
    & .\gradlew.bat -p $prototypeRoot :core:testClasses --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Core test compilation failed: $LASTEXITCODE" }

    $mainClasses = Join-Path $prototypeRoot 'core\build\classes\kotlin\main'
    $testClasses = Join-Path $prototypeRoot 'core\build\classes\kotlin\test'
    $testResources = Join-Path $prototypeRoot 'core\build\resources\test'
    $junit = Find-DependencyJar "$moduleCache\junit\junit\4.13.2" 'junit-4.13.2.jar'
    $hamcrest = Find-DependencyJar "$moduleCache\org.hamcrest\hamcrest-core\1.3" 'hamcrest-core-1.3.jar'
    $kotlin = Find-DependencyJar "$moduleCache\org.jetbrains.kotlin\kotlin-stdlib\2.0.0" 'kotlin-stdlib-2.0.0.jar'
    $classPath = @($mainClasses, $testClasses, $testResources, $junit, $hamcrest, $kotlin) -join ';'

    $testNames = @(Get-ChildItem -LiteralPath $testClasses -Filter '*Test.class' -Recurse |
        Where-Object { $_.Name -notmatch '\$' } |
        ForEach-Object { $_.FullName.Substring($testClasses.Length + 1).Replace('\', '.').Replace('.class', '') })
    if (-not $testNames) { throw 'No test classes found' }

    & $java -cp $classPath org.junit.runner.JUnitCore @testNames
    if ($LASTEXITCODE -ne 0) { throw "Core tests failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
