# Offline build preparation.
#
# Does two things:
#   1) lays the Gradle artifact cache (caches/modules-2/files-2.1) out as a plain Maven repository
#      in <project>/local-maven, together with the maven-metadata.xml files needed by dynamic
#      versions (e.g. log4j-api:2.11.+);
#   2) copies the cached MCP config zip to build/downloadMcpConfig/output.zip, so the ForgeGradle
#      task of that name can be skipped (-x downloadMcpConfig) instead of trying to reach the network.
#
# Usage:
#   powershell -File tools/offline-build-setup.ps1 -GradleHome '<your gradle user home>'
#
# Then build with:
#   <gradle>/bin/gradle.bat --offline --no-daemon build -x downloadMcpConfig

param(
    [Parameter(Mandatory = $true)][string]$GradleHome
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$files21    = Join-Path $GradleHome 'caches\modules-2\files-2.1'
$localMaven = Join-Path $projectDir 'local-maven'
$fgCache    = Join-Path $GradleHome 'caches\forge_gradle\maven_downloader'

if (-not (Test-Path -LiteralPath $files21)) {
    throw "Gradle artifact cache not found: $files21 (run a network build once first)"
}

New-Item -ItemType Directory -Force -Path $localMaven | Out-Null

# ---- 1) Maven layout + maven-metadata.xml ----
$copied = 0
$synth  = 0
$meta   = 0

foreach ($group in Get-ChildItem -LiteralPath $files21 -Directory) {
    $groupPath = $group.Name.Replace('.', '\')
    foreach ($art in Get-ChildItem -LiteralPath $group.FullName -Directory) {
        $versions = @(Get-ChildItem -LiteralPath $art.FullName -Directory | Select-Object -ExpandProperty Name)
        if ($versions.Count -eq 0) { continue }

        foreach ($ver in Get-ChildItem -LiteralPath $art.FullName -Directory) {
            $target = Join-Path $localMaven ($groupPath + '\' + $art.Name + '\' + $ver.Name)
            $hasPom = $false
            $hasJar = $false
            foreach ($hash in Get-ChildItem -LiteralPath $ver.FullName -Directory) {
                foreach ($f in Get-ChildItem -LiteralPath $hash.FullName -File) {
                    $ext = $f.Extension.ToLowerInvariant()
                    if ($ext -ne '.jar' -and $ext -ne '.pom' -and $ext -ne '.module') { continue }
                    if (-not (Test-Path -LiteralPath $target)) { New-Item -ItemType Directory -Force -Path $target | Out-Null }
                    Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $target $f.Name) -Force
                    $copied++
                    if ($ext -eq '.pom') { $hasPom = $true }
                    if ($ext -eq '.jar') { $hasJar = $true }
                }
            }
            # Modules that only ship a jar get a minimal POM, otherwise a Maven repository cannot resolve them.
            if ($hasJar -and -not $hasPom) {
                New-Item -ItemType Directory -Force -Path $target | Out-Null
                $pomPath = Join-Path $target ($art.Name + '-' + $ver.Name + '.pom')
                $pomText = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>' +
                           $group.Name + '</groupId><artifactId>' + $art.Name + '</artifactId><version>' + $ver.Name + '</version></project>'
                [System.IO.File]::WriteAllText($pomPath, $pomText)
                $synth++
            }
        }

        $metaTarget = Join-Path $localMaven ($groupPath + '\' + $art.Name)
        if (-not (Test-Path -LiteralPath $metaTarget)) { continue }
        $metaXml = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" + '<metadata>' + "`n" +
                   '  <groupId>' + $group.Name + '</groupId>' + "`n" +
                   '  <artifactId>' + $art.Name + '</artifactId>' + "`n" +
                   '  <versioning>' + "`n" +
                   '    <latest>' + $versions[-1] + '</latest>' + "`n" +
                   '    <release>' + $versions[-1] + '</release>' + "`n" +
                   '    <versions>' + "`n" +
                   (($versions | ForEach-Object { '      <version>' + $_ + '</version>' }) -join "`n") + "`n" +
                   '    </versions>' + "`n" +
                   '    <lastUpdated>20240101000000</lastUpdated>' + "`n" +
                   '  </versioning>' + "`n" +
                   '</metadata>' + "`n"
        [System.IO.File]::WriteAllText((Join-Path $metaTarget 'maven-metadata.xml'), $metaXml)
        $meta++
    }
}

"local-maven: copied=$copied synthesizedPom=$synth metadata=$meta"

# ---- 2) Provide the artifact of the skipped downloadMcpConfig task ----
$mcpDir = Join-Path $fgCache 'de\oceanlabs\mcp\mcp_config'
$mcpZip = Get-ChildItem -LiteralPath $mcpDir -Recurse -Filter 'mcp_config-*.zip' -ErrorAction SilentlyContinue |
          Where-Object { $_.Name -notmatch '\.md5$' } | Sort-Object Length -Descending | Select-Object -First 1
$outDir = Join-Path $projectDir 'build\downloadMcpConfig'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if ($mcpZip) {
    Copy-Item -LiteralPath $mcpZip.FullName -Destination (Join-Path $outDir 'output.zip') -Force
    "seeded build/downloadMcpConfig/output.zip  <=  " + $mcpZip.Name
} else {
    "warning: no mcp_config*.zip in $mcpDir, skipped; a network build will download it"
}
