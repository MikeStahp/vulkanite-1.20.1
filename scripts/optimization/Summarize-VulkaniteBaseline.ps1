[CmdletBinding()]
param(
    [string]$CaptureRoot = 'run/vulkanite-baselines',
    [string]$OutputFile = 'run/vulkanite-baselines/summary.csv'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..'))
Set-Location $repoRoot

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if (-not $Values -or $Values.Count -eq 0) { return [double]::NaN }
    $sorted = @($Values | Sort-Object)
    $rank = [math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $rank = [math]::Max(0, [math]::Min($rank, $sorted.Count - 1))
    return [double]$sorted[$rank]
}

function Get-StdDev {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -lt 2) { return [double]::NaN }
    $mean = ($Values | Measure-Object -Average).Average
    $sumSqDiff = 0.0
    foreach ($v in $Values) { $sumSqDiff += ($v - $mean) * ($v - $mean) }
    return [math]::Sqrt($sumSqDiff / ($Values.Count - 1))
}

function Get-CoV {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -lt 2) { return [double]::NaN }
    $mean = ($Values | Measure-Object -Average).Average
    if ($mean -eq 0) { return [double]::NaN }
    $stdev = Get-StdDev -Values $Values
    return [math]::Round(100.0 * $stdev / $mean, 4)
}

$summaries = foreach ($csvFile in Get-ChildItem -LiteralPath $CaptureRoot -Filter 'presentmon.csv' -File -Recurse) {
    $captureDir = $csvFile.Directory.FullName
    $manifestPath = Join-Path $captureDir 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        Write-Warning "Skipping capture without manifest: $captureDir"
        continue
    }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $rows = @(Import-Csv -LiteralPath $csvFile.FullName)
    if ($rows.Count -eq 0) {
        Write-Warning "Skipping empty capture: $captureDir"
        continue
    }
    $columns = @($rows[0].PSObject.Properties.Name)
    $cpuColumn = @('CPUFrameTime', 'msBetweenPresents') | Where-Object { $columns -contains $_ } | Select-Object -First 1
    $gpuColumn = @('GPUTime', 'msGPUActive') | Where-Object { $columns -contains $_ } | Select-Object -First 1
    if (-not $cpuColumn) {
        throw "No recognized CPU frame-time column in $($csvFile.FullName). Columns: $($columns -join ', ')"
    }

    [double[]]$cpu = @($rows | ForEach-Object { [double]($_.$cpuColumn) })
    [double[]]$gpu = if ($gpuColumn) { @($rows | ForEach-Object { [double]($_.$gpuColumn) }) } else { @() }

    # Read flags from manifest
    $tag = if ($manifest.PSObject.Properties.Name -contains 'tag') { $manifest.tag } else { 'baseline' }
    $outlierWarning = if ($manifest.PSObject.Properties.Name -contains 'outlier_warning') { $manifest.outlier_warning } else { $false }
    $outlierPct = if ($manifest.PSObject.Properties.Name -contains 'outlier_pct') { $manifest.outlier_pct } else { 0.0 }
    $frameValidated = if ($manifest.PSObject.Properties.Name -contains 'frame_count_validated') { $manifest.frame_count_validated } else { ($rows.Count -ge 120) }
    $gpuGuardPassed = $true
    if ($manifest.PSObject.Properties.Name -contains 'gpu_guard') {
        $gpuGuardPassed = $manifest.gpu_guard.passed
    }

    [pscustomobject]@{
        capture_id = $manifest.capture_id
        tag = $tag
        build_id = $manifest.git_commit
        settings_hash = $manifest.settings_hash
        scene = $manifest.scene
        run = $manifest.run
        frame_count = $rows.Count
        accepted_sample_count = $frameValidated
        cpu_metric = $cpuColumn
        cpu_min_ms = [math]::Round(($cpu | Measure-Object -Minimum).Minimum, 4)
        cpu_max_ms = [math]::Round(($cpu | Measure-Object -Maximum).Maximum, 4)
        cpu_median_ms = [math]::Round((Get-Percentile $cpu 50), 4)
        cpu_p95_ms = [math]::Round((Get-Percentile $cpu 95), 4)
        cpu_p99_ms = [math]::Round((Get-Percentile $cpu 99), 4)
        cpu_stdev_ms = [math]::Round((Get-StdDev $cpu), 4)
        gpu_metric = if ($gpuColumn) { $gpuColumn } else { '' }
        gpu_min_ms = if ($gpu.Count) { [math]::Round(($gpu | Measure-Object -Minimum).Minimum, 4) } else { '' }
        gpu_max_ms = if ($gpu.Count) { [math]::Round(($gpu | Measure-Object -Maximum).Maximum, 4) } else { '' }
        gpu_median_ms = if ($gpu.Count) { [math]::Round((Get-Percentile $gpu 50), 4) } else { '' }
        gpu_p95_ms = if ($gpu.Count) { [math]::Round((Get-Percentile $gpu 95), 4) } else { '' }
        gpu_p99_ms = if ($gpu.Count) { [math]::Round((Get-Percentile $gpu 99), 4) } else { '' }
        gpu_stdev_ms = if ($gpu.Count -ge 2) { [math]::Round((Get-StdDev $gpu), 4) } else { '' }
        outlier_warning = $outlierWarning
        outlier_pct = $outlierPct
        gpu_guard_passed = $gpuGuardPassed
        source = $csvFile.FullName
    }
}

$summaries = @($summaries | Sort-Object tag, scene, run)
if ($summaries.Count -eq 0) {
    throw "No valid captures were found below $CaptureRoot"
}
$outputDir = Split-Path -Parent $OutputFile
if ($outputDir) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }
$summaries | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8

# --- Per-scene summary table ---
Write-Host "`n=== Per-Capture Results ===" -ForegroundColor Cyan
$summaries | Format-Table tag, scene, run, frame_count, accepted_sample_count, cpu_median_ms, cpu_p95_ms, cpu_p99_ms, cpu_stdev_ms, gpu_median_ms, gpu_p95_ms, gpu_p99_ms, gpu_stdev_ms, outlier_warning

# --- Run-to-run spread (CoV) per scene+tag ---
Write-Host "=== Run-to-Run Spread (CoV%) ===" -ForegroundColor Cyan
$groups = $summaries | Group-Object { "$($_.tag)|$($_.scene)" }
foreach ($g in $groups) {
    $parts = $g.Name -split '\|'
    $gTag = $parts[0]
    $gScene = $parts[1]
    $medians = @($g.Group | ForEach-Object { [double]$_.cpu_median_ms })
    $gpuMedians = @($g.Group | Where-Object { $_.gpu_median_ms -ne '' } | ForEach-Object { [double]$_.gpu_median_ms })

    $cpuCoV = Get-CoV -Values $medians
    $gpuCoV = if ($gpuMedians.Count -ge 2) { Get-CoV -Values $gpuMedians } else { [double]::NaN }

    $cpuColor = if ([double]::IsNaN($cpuCoV) -or $cpuCoV -lt 5) { 'Green' } elseif ($cpuCoV -lt 10) { 'Yellow' } else { 'Red' }
    $gpuColor = if ([double]::IsNaN($gpuCoV) -or $gpuCoV -lt 5) { 'Green' } elseif ($gpuCoV -lt 10) { 'Yellow' } else { 'Red' }

    $cpuStr = if ([double]::IsNaN($cpuCoV)) { 'N/A (1 run)' } else { "${cpuCoV}%" }
    $gpuStr = if ([double]::IsNaN($gpuCoV)) { 'N/A' } else { "${gpuCoV}%" }

    Write-Host -NoNewline "  [$gTag] $gScene  CPU CoV: "
    Write-Host -NoNewline $cpuStr -ForegroundColor $cpuColor
    Write-Host -NoNewline "  GPU CoV: "
    Write-Host $gpuStr -ForegroundColor $gpuColor

    # Warn on flagged captures
    $flagged = @($g.Group | Where-Object { $_.outlier_warning -eq $true -or $_.gpu_guard_passed -eq $false })
    if ($flagged.Count -gt 0) {
        foreach ($f in $flagged) {
            $reasons = @()
            if ($f.outlier_warning) { $reasons += "outliers=$($f.outlier_pct)%" }
            if (-not $f.gpu_guard_passed) { $reasons += 'thermal/power' }
            Write-Host "    WARNING: run $($f.run) flagged ($($reasons -join ', '))" -ForegroundColor Yellow
        }
    }
}

Write-Host "`nSummary written to $OutputFile"
