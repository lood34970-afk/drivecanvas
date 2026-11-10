param([string]$OsmDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'data\osm'))
$ErrorActionPreference = 'Stop'

# Offline preprocessing: OSM tiles -> DCG graph, DCP placements and SVG previews under app assets.
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

if (-not (Test-Path -LiteralPath $OsmDir)) { throw "OSM directory not found: $OsmDir" }

$env:JAVA_HOME = $javaHome
Push-Location $prototypeRoot
try {
    & .\gradlew.bat -p $prototypeRoot :core:classes --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Core compilation failed: $LASTEXITCODE" }

    $mainClasses = Join-Path $prototypeRoot 'core\build\classes\kotlin\main'
    $kotlin = Find-DependencyJar "$moduleCache\org.jetbrains.kotlin\kotlin-stdlib\2.0.0" 'kotlin-stdlib-2.0.0.jar'
    $classPath = @($mainClasses, $kotlin) -join ';'
    $canvasOut = Join-Path $prototypeRoot 'app\src\main\assets\canvas'

    & $java '-Dfile.encoding=UTF-8' -cp $classPath com.example.drivecanvas.core.tools.PreprocessKt --osm $OsmDir --out $canvasOut
    if ($LASTEXITCODE -ne 0) { throw "Preprocess failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
