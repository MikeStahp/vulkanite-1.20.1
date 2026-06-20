[CmdletBinding()]
param(
    [string]$World = 'ray-tracing-test-place'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $repoRoot 'tools/vulkanite-benchmark-datapack'
$worldRoot = Join-Path $repoRoot (Join-Path 'run\saves' $World)
$destination = Join-Path $worldRoot 'datapacks\vulkanite-benchmark'

if (-not (Test-Path -LiteralPath (Join-Path $worldRoot 'level.dat'))) {
    throw "Minecraft world not found: $worldRoot"
}
if (-not (Test-Path -LiteralPath (Join-Path $source 'pack.mcmeta'))) {
    throw "Benchmark datapack source not found: $source"
}

$activeGame = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -eq 'java.exe' -and
        $_.CommandLine -match '(devlaunchinjector\.Main|KnotClient|net\.minecraft\.client\.main\.Main)'
    } |
    Select-Object -First 1
if ($activeGame) {
    throw "Minecraft is running as PID $($activeGame.ProcessId). Exit the world before installing the datapack."
}

New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $destination -Recurse -Force
Write-Host "Installed Vulkanite benchmark datapack into $destination"
Write-Host "Open the world once; the lab builds automatically at X=480..992, Z=-48..48."
