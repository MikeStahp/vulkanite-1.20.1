<#
.SYNOPSIS
    Compare baseline and optimization captures with statistical significance testing.
.DESCRIPTION
    Loads summaries from two capture sets (baseline vs optimization), matches scenes,
    computes Welch's t-test on CPU and GPU frame-time medians across runs, and produces
    a per-scene verdict based on the acceptance thresholds defined in the optimization
    baseline README.
.EXAMPLE
    ./scripts/optimization/Compare-VulkaniteOptimization.ps1 `
        -BaselineRoot 'run/vulkanite-baselines/baseline' `
        -OptimizationRoot 'run/vulkanite-baselines/optimization'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineRoot,

    [Parameter(Mandatory = $true)]
    [string]$OptimizationRoot,

    [string]$OutputFile = 'run/vulkanite-baselines/comparison.csv',

    [double]$MinMedianGainPct = 3.0,
    [double]$MaxTailRegressionPct = 2.0,
    [double]$ConfidenceLevel = 0.95
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..'))
Set-Location $repoRoot

# --- Helper functions ---

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if (-not $Values -or $Values.Count -eq 0) { return [double]::NaN }
    $sorted = @($Values | Sort-Object)
    $rank = [math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $rank = [math]::Max(0, [math]::Min($rank, $sorted.Count - 1))
    return [double]$sorted[$rank]
}

function Get-Mean {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -eq 0) { return [double]::NaN }
    return ($Values | Measure-Object -Average).Average
}

function Get-StdDev {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -lt 2) { return [double]::NaN }
    $mean = Get-Mean -Values $Values
    $sumSqDiff = 0.0
    foreach ($v in $Values) { $sumSqDiff += ($v - $mean) * ($v - $mean) }
    return [math]::Sqrt($sumSqDiff / ($Values.Count - 1))
}

function Get-CoV {
    param([double[]]$Values)
    if (-not $Values -or $Values.Count -lt 2) { return [double]::NaN }
    $mean = Get-Mean -Values $Values
    if ($mean -eq 0) { return [double]::NaN }
    return 100.0 * (Get-StdDev -Values $Values) / $mean
}

function Get-WelchTTest {
    <#
    .SYNOPSIS
        Computes Welch's t-test (two-sample, unequal variance) for two groups.
        Returns t-statistic, degrees of freedom, and approximate two-tailed p-value.
    #>
    param(
        [double[]]$GroupA,
        [double[]]$GroupB
    )
    $nA = $GroupA.Count
    $nB = $GroupB.Count
    if ($nA -lt 2 -or $nB -lt 2) {
        return [ordered]@{ t = [double]::NaN; df = [double]::NaN; p = [double]::NaN; sufficient = $false }
    }
    $meanA = Get-Mean -Values $GroupA
    $meanB = Get-Mean -Values $GroupB
    $varA = (Get-StdDev -Values $GroupA)
    $varA = $varA * $varA
    $varB = (Get-StdDev -Values $GroupB)
    $varB = $varB * $varB

    $seA = $varA / $nA
    $seB = $varB / $nB
    $seDiff = [math]::Sqrt($seA + $seB)

    if ($seDiff -eq 0) {
        return [ordered]@{ t = 0.0; df = ($nA + $nB - 2); p = 1.0; sufficient = $true }
    }

    $t = ($meanA - $meanB) / $seDiff

    # Welch-Satterthwaite degrees of freedom
    $num = ($seA + $seB) * ($seA + $seB)
    $denA = if ($nA -gt 1) { $seA * $seA / ($nA - 1) } else { 0 }
    $denB = if ($nB -gt 1) { $seB * $seB / ($nB - 1) } else { 0 }
    $den = $denA + $denB
    $df = if ($den -gt 0) { $num / $den } else { $nA + $nB - 2 }

    # Approximate p-value using normal approximation for large df, or conservative estimate
    # (PowerShell doesn't have a t-distribution CDF, so we use a conservative approximation)
    $absT = [math]::Abs($t)
    if ($df -ge 30) {
        # Normal approximation for large df
        $z = $absT
        # Abramowitz & Stegun approximation for normal CDF complement
        $b0 = 0.2316419; $b1 = 0.319381530; $b2 = -0.356563782
        $b3 = 1.781477937; $b4 = -1.821255978; $b5 = 1.330274429
        $tt = 1.0 / (1.0 + $b0 * $z)
        $phi = [math]::Exp(-0.5 * $z * $z) / [math]::Sqrt(2 * [math]::PI)
        $pOneTail = $phi * ($b1 * $tt + $b2 * $tt * $tt + $b3 * [math]::Pow($tt, 3) + $b4 * [math]::Pow($tt, 4) + $b5 * [math]::Pow($tt, 5))
        $p = 2.0 * [math]::Max(0, [math]::Min(1, $pOneTail))
    } else {
        # Conservative: if |t| > 4.3 with df >= 2, p < 0.05
        # Use very rough critical value lookup
        $p = if ($absT -gt 12.71 -and $df -ge 1) { 0.001 }
             elseif ($absT -gt 4.303 -and $df -ge 2) { 0.01 }
             elseif ($absT -gt 3.182 -and $df -ge 3) { 0.02 }
             elseif ($absT -gt 2.776 -and $df -ge 4) { 0.03 }
             elseif ($absT -gt 2.571 -and $df -ge 5) { 0.04 }
             elseif ($absT -gt 2.447 -and $df -ge 6) { 0.05 }
             elseif ($absT -gt 2.0) { 0.10 }
             elseif ($absT -gt 1.5) { 0.20 }
             else { 0.50 }
    }

    return [ordered]@{
        t = [math]::Round($t, 4)
        df = [math]::Round($df, 2)
        p = [math]::Round($p, 6)
        sufficient = $true
    }
}

# --- Load captures from a root directory ---
function Get-CaptureData {
    param([string]$Root)
    $results = @{}
    foreach ($csvFile in Get-ChildItem -LiteralPath $Root -Filter 'presentmon.csv' -File -Recurse) {
        $captureDir = $csvFile.Directory.FullName
        $manifestPath = Join-Path $captureDir 'manifest.json'
        if (-not (Test-Path -LiteralPath $manifestPath)) { continue }
        $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        $rows = @(Import-Csv -LiteralPath $csvFile.FullName)
        if ($rows.Count -eq 0) { continue }

        $columns = @($rows[0].PSObject.Properties.Name)
        $cpuCol = @('CPUFrameTime', 'msBetweenPresents') | Where-Object { $columns -contains $_ } | Select-Object -First 1
        $gpuCol = @('GPUTime', 'msGPUActive') | Where-Object { $columns -contains $_ } | Select-Object -First 1
        if (-not $cpuCol) { continue }

        [double[]]$cpu = @($rows | ForEach-Object { [double]($_.$cpuCol) })
        [double[]]$gpu = if ($gpuCol) { @($rows | ForEach-Object { [double]($_.$gpuCol) }) } else { @() }

        $scene = $manifest.scene
        if (-not $results.ContainsKey($scene)) {
            $results[$scene] = @{
                settings_hashes = @()
                runs = @()
            }
        }
        $results[$scene].settings_hashes += $manifest.settings_hash
        $results[$scene].runs += [pscustomobject]@{
            run = $manifest.run
            cpu_median = Get-Percentile $cpu 50
            cpu_p95 = Get-Percentile $cpu 95
            cpu_p99 = Get-Percentile $cpu 99
            gpu_median = if ($gpu.Count) { Get-Percentile $gpu 50 } else { [double]::NaN }
            gpu_p95 = if ($gpu.Count) { Get-Percentile $gpu 95 } else { [double]::NaN }
            gpu_p99 = if ($gpu.Count) { Get-Percentile $gpu 99 } else { [double]::NaN }
            frame_count = $rows.Count
        }
    }
    return $results
}

# --- Validate paths ---
if (-not (Test-Path -LiteralPath $BaselineRoot)) {
    throw "Baseline root not found: $BaselineRoot"
}
if (-not (Test-Path -LiteralPath $OptimizationRoot)) {
    throw "Optimization root not found: $OptimizationRoot"
}

# --- Load data ---
Write-Host 'Loading baseline captures...' -ForegroundColor Cyan
$baseline = Get-CaptureData -Root $BaselineRoot
Write-Host "  Found $($baseline.Keys.Count) scene(s) with baseline data"

Write-Host 'Loading optimization captures...' -ForegroundColor Cyan
$optimization = Get-CaptureData -Root $OptimizationRoot
Write-Host "  Found $($optimization.Keys.Count) scene(s) with optimization data"

# --- Match and compare scenes ---
$allScenes = @($baseline.Keys) + @($optimization.Keys) | Sort-Object -Unique
$comparisons = @()
$verdicts = @()

foreach ($scene in $allScenes) {
    $hasBaseline = $baseline.ContainsKey($scene)
    $hasOpt = $optimization.ContainsKey($scene)

    if (-not $hasBaseline) {
        Write-Warning "Scene '$scene' exists in optimization but not in baseline. Skipping."
        continue
    }
    if (-not $hasOpt) {
        Write-Warning "Scene '$scene' exists in baseline but not in optimization. Skipping."
        continue
    }

    $bData = $baseline[$scene]
    $oData = $optimization[$scene]

    # Settings hash validation
    $bHashes = @($bData.settings_hashes | Sort-Object -Unique)
    $oHashes = @($oData.settings_hashes | Sort-Object -Unique)
    $hashMatch = ($bHashes.Count -eq 1 -and $oHashes.Count -eq 1 -and $bHashes[0] -eq $oHashes[0])
    if (-not $hashMatch) {
        Write-Warning "Scene '$scene': Settings hash mismatch between baseline and optimization. Results may not be comparable."
    }

    $bRuns = @($bData.runs)
    $oRuns = @($oData.runs)

    # CPU median comparison
    [double[]]$bCpuMedians = @($bRuns | ForEach-Object { $_.cpu_median })
    [double[]]$oCpuMedians = @($oRuns | ForEach-Object { $_.cpu_median })
    $bCpuMean = Get-Mean -Values $bCpuMedians
    $oCpuMean = Get-Mean -Values $oCpuMedians
    $cpuDeltaMs = [math]::Round($oCpuMean - $bCpuMean, 4)
    $cpuDeltaPct = if ($bCpuMean -ne 0) { [math]::Round(100.0 * $cpuDeltaMs / $bCpuMean, 2) } else { 0 }
    $cpuTest = Get-WelchTTest -GroupA $bCpuMedians -GroupB $oCpuMedians
    $bCpuCoV = Get-CoV -Values $bCpuMedians

    # GPU median comparison
    [double[]]$bGpuMedians = @($bRuns | Where-Object { -not [double]::IsNaN($_.gpu_median) } | ForEach-Object { $_.gpu_median })
    [double[]]$oGpuMedians = @($oRuns | Where-Object { -not [double]::IsNaN($_.gpu_median) } | ForEach-Object { $_.gpu_median })
    $bGpuMean = Get-Mean -Values $bGpuMedians
    $oGpuMean = Get-Mean -Values $oGpuMedians
    $gpuDeltaMs = [math]::Round($oGpuMean - $bGpuMean, 4)
    $gpuDeltaPct = if ($bGpuMean -ne 0) { [math]::Round(100.0 * $gpuDeltaMs / $bGpuMean, 2) } else { 0 }
    $gpuTest = Get-WelchTTest -GroupA $bGpuMedians -GroupB $oGpuMedians

    # P95 comparison (non-regression)
    [double[]]$bCpuP95 = @($bRuns | ForEach-Object { $_.cpu_p95 })
    [double[]]$oCpuP95 = @($oRuns | ForEach-Object { $_.cpu_p95 })
    $bCpuP95Mean = Get-Mean -Values $bCpuP95
    $oCpuP95Mean = Get-Mean -Values $oCpuP95
    $cpuP95DeltaPct = if ($bCpuP95Mean -ne 0) { [math]::Round(100.0 * ($oCpuP95Mean - $bCpuP95Mean) / $bCpuP95Mean, 2) } else { 0 }

    # P99 comparison (non-regression)
    [double[]]$bCpuP99 = @($bRuns | ForEach-Object { $_.cpu_p99 })
    [double[]]$oCpuP99 = @($oRuns | ForEach-Object { $_.cpu_p99 })
    $bCpuP99Mean = Get-Mean -Values $bCpuP99
    $oCpuP99Mean = Get-Mean -Values $oCpuP99
    $cpuP99DeltaPct = if ($bCpuP99Mean -ne 0) { [math]::Round(100.0 * ($oCpuP99Mean - $bCpuP99Mean) / $bCpuP99Mean, 2) } else { 0 }

    # --- Apply acceptance thresholds ---
    # Median gain must exceed max(MinMedianGainPct, baseline CoV)
    $effectiveMedianThreshold = [math]::Max($MinMedianGainPct, $(if ([double]::IsNaN($bCpuCoV)) { $MinMedianGainPct } else { $bCpuCoV }))
    # Tail regression must not exceed max(MaxTailRegressionPct, baseline CoV)
    $effectiveTailThreshold = [math]::Max($MaxTailRegressionPct, $(if ([double]::IsNaN($bCpuCoV)) { $MaxTailRegressionPct } else { $bCpuCoV }))

    $medianImproved = ($cpuDeltaPct -lt 0) -and ([math]::Abs($cpuDeltaPct) -gt $effectiveMedianThreshold)
    $p95Regressed = ($cpuP95DeltaPct -gt $effectiveTailThreshold)
    $p99Regressed = ($cpuP99DeltaPct -gt $effectiveTailThreshold)
    $significant = $cpuTest.sufficient -and ($cpuTest.p -lt (1 - $ConfidenceLevel))
    $sufficientRuns = ($bRuns.Count -ge 3 -and $oRuns.Count -ge 3)

    $verdict = if ($p95Regressed -or $p99Regressed) {
        'FAIL'
    } elseif (-not $sufficientRuns) {
        'INCONCLUSIVE'
    } elseif (-not $significant) {
        'INCONCLUSIVE'
    } elseif ($medianImproved) {
        'PASS'
    } else {
        'NEUTRAL'
    }

    $verdicts += $verdict

    $comparisons += [pscustomobject]@{
        scene = $scene
        settings_match = $hashMatch
        baseline_runs = $bRuns.Count
        optimization_runs = $oRuns.Count
        cpu_baseline_ms = [math]::Round($bCpuMean, 4)
        cpu_optimization_ms = [math]::Round($oCpuMean, 4)
        cpu_delta_ms = $cpuDeltaMs
        cpu_delta_pct = $cpuDeltaPct
        cpu_t = $cpuTest.t
        cpu_df = $cpuTest.df
        cpu_p = $cpuTest.p
        cpu_p95_delta_pct = $cpuP95DeltaPct
        cpu_p99_delta_pct = $cpuP99DeltaPct
        gpu_baseline_ms = if (-not [double]::IsNaN($bGpuMean)) { [math]::Round($bGpuMean, 4) } else { '' }
        gpu_optimization_ms = if (-not [double]::IsNaN($oGpuMean)) { [math]::Round($oGpuMean, 4) } else { '' }
        gpu_delta_ms = if (-not [double]::IsNaN($gpuDeltaMs)) { $gpuDeltaMs } else { '' }
        gpu_delta_pct = if (-not [double]::IsNaN($gpuDeltaPct)) { $gpuDeltaPct } else { '' }
        gpu_t = $gpuTest.t
        gpu_p = $gpuTest.p
        effective_median_threshold_pct = [math]::Round($effectiveMedianThreshold, 2)
        effective_tail_threshold_pct = [math]::Round($effectiveTailThreshold, 2)
        verdict = $verdict
    }
}

# --- Output ---
if ($comparisons.Count -eq 0) {
    throw 'No matching scenes found between baseline and optimization captures.'
}

$outputDir = Split-Path -Parent $OutputFile
if ($outputDir) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }
$comparisons | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8

Write-Host "`n=== A/B Comparison Results ===" -ForegroundColor Cyan
Write-Host "Baseline:     $BaselineRoot"
Write-Host "Optimization: $OptimizationRoot"
Write-Host ""

foreach ($c in $comparisons) {
    $color = switch ($c.verdict) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'NEUTRAL' { 'White' }
        default { 'Yellow' }
    }
    Write-Host -NoNewline "  [$($c.verdict)] " -ForegroundColor $color
    Write-Host -NoNewline "$($c.scene)" -ForegroundColor White
    Write-Host -NoNewline "  CPU: $($c.cpu_baseline_ms) -> $($c.cpu_optimization_ms) ms"
    Write-Host -NoNewline " ($($c.cpu_delta_pct)%)"
    if ($c.cpu_p -ne '' -and -not [double]::IsNaN($c.cpu_p)) {
        Write-Host -NoNewline "  p=$($c.cpu_p)"
    }
    if ($c.gpu_baseline_ms -ne '' -and $c.gpu_optimization_ms -ne '') {
        Write-Host -NoNewline "  GPU: $($c.gpu_baseline_ms) -> $($c.gpu_optimization_ms) ms ($($c.gpu_delta_pct)%)"
    }
    Write-Host ''
    if ($c.cpu_p95_delta_pct -gt 0 -or $c.cpu_p99_delta_pct -gt 0) {
        $tailColor = if ($c.cpu_p95_delta_pct -gt $c.effective_tail_threshold_pct -or $c.cpu_p99_delta_pct -gt $c.effective_tail_threshold_pct) { 'Red' } else { 'Yellow' }
        Write-Host "    P95 delta: $($c.cpu_p95_delta_pct)%  P99 delta: $($c.cpu_p99_delta_pct)%  (threshold: $($c.effective_tail_threshold_pct)%)" -ForegroundColor $tailColor
    }
    if (-not $c.settings_match) {
        Write-Host '    WARNING: Settings hash mismatch' -ForegroundColor Yellow
    }
}

# --- Overall verdict ---
$overallVerdict = if ($verdicts -contains 'FAIL') {
    'FAIL'
} elseif ($verdicts -contains 'INCONCLUSIVE') {
    'MIXED'
} elseif (($verdicts | Where-Object { $_ -eq 'PASS' }).Count -eq $verdicts.Count) {
    'PASS'
} else {
    'MIXED'
}

$overallColor = switch ($overallVerdict) {
    'PASS' { 'Green' }
    'FAIL' { 'Red' }
    default { 'Yellow' }
}

Write-Host ""
Write-Host "Overall: " -NoNewline
Write-Host $overallVerdict -ForegroundColor $overallColor
Write-Host "Comparison written to $OutputFile"
