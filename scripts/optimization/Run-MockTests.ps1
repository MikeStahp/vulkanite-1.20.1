[CmdletBinding()]
param(
    [string]$TestRoot = 'run/vulkanite-baselines-test'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repoRoot

if (Test-Path -LiteralPath $TestRoot) {
    Remove-Item -Path $TestRoot -Recurse -Force
}

function Create-MockCapture {
    param(
        [string]$Tag,
        [string]$Scene,
        [int]$Run,
        [double]$CpuBase,
        [double]$GpuBase,
        [double]$Variance
    )

    $captureDir = Join-Path $TestRoot (Join-Path $Tag "test-$Scene-run$Run")
    New-Item -ItemType Directory -Path $captureDir -Force | Out-Null

    # Create manifest
    $manifest = [ordered]@{
        capture_id = "test-$Scene-run$Run"
        tag = $Tag
        captured_at = (Get-Date).ToString('o')
        git_commit = 'testcommit'
        settings_hash = 'testhash'
        scene = $Scene
        run = $Run
        duration_seconds = 15
        warmup_seconds = 0
        process_id = 1234
        frame_count_validated = $true
        outlier_warning = $false
        outlier_pct = 0.0
        gpu_guard = [ordered]@{ passed = $true; warnings = @() }
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $captureDir 'manifest.json') -Encoding UTF8

    # Create mock PresentMon CSV
    $csvLines = @("CPUFrameTime,msBetweenPresents,GPUTime,msGPUActive")
    $rand = [System.Random]::new()
    for ($i = 0; $i -lt 150; $i++) {
        # Add some noise
        $noise = ($rand.NextDouble() - 0.5) * 2.0 * $Variance
        $cpu = $CpuBase + $noise
        
        # Occasional spike
        if ($rand.NextDouble() -gt 0.95) { $cpu += $Variance * 5.0 }

        $gpu = $GpuBase + $noise * 0.5
        $csvLines += "$cpu,$cpu,$gpu,$gpu"
    }
    $csvLines | Set-Content -LiteralPath (Join-Path $captureDir 'presentmon.csv') -Encoding UTF8
}

Write-Host "Generating mock baseline captures..."
Create-MockCapture -Tag 'baseline' -Scene 'static-exterior' -Run 1 -CpuBase 16.5 -GpuBase 14.0 -Variance 0.5
Create-MockCapture -Tag 'baseline' -Scene 'static-exterior' -Run 2 -CpuBase 16.6 -GpuBase 14.1 -Variance 0.5
Create-MockCapture -Tag 'baseline' -Scene 'static-exterior' -Run 3 -CpuBase 16.4 -GpuBase 13.9 -Variance 0.5

Create-MockCapture -Tag 'baseline' -Scene 'stress-entities' -Run 1 -CpuBase 25.0 -GpuBase 18.0 -Variance 1.5
Create-MockCapture -Tag 'baseline' -Scene 'stress-entities' -Run 2 -CpuBase 25.5 -GpuBase 18.2 -Variance 1.5
Create-MockCapture -Tag 'baseline' -Scene 'stress-entities' -Run 3 -CpuBase 24.8 -GpuBase 17.9 -Variance 1.5

Write-Host "Generating mock optimization captures (simulating a ~10% win on exterior, no change on entities)..."
Create-MockCapture -Tag 'optimization' -Scene 'static-exterior' -Run 1 -CpuBase 14.5 -GpuBase 12.5 -Variance 0.4
Create-MockCapture -Tag 'optimization' -Scene 'static-exterior' -Run 2 -CpuBase 14.6 -GpuBase 12.6 -Variance 0.4
Create-MockCapture -Tag 'optimization' -Scene 'static-exterior' -Run 3 -CpuBase 14.4 -GpuBase 12.4 -Variance 0.4

Create-MockCapture -Tag 'optimization' -Scene 'stress-entities' -Run 1 -CpuBase 24.9 -GpuBase 18.1 -Variance 1.5
Create-MockCapture -Tag 'optimization' -Scene 'stress-entities' -Run 2 -CpuBase 25.1 -GpuBase 18.0 -Variance 1.5
Create-MockCapture -Tag 'optimization' -Scene 'stress-entities' -Run 3 -CpuBase 25.0 -GpuBase 18.2 -Variance 1.5

Write-Host "`n--- Running Summarize Script (Baseline) ---"
& powershell -NoProfile -File "scripts/optimization/Summarize-VulkaniteBaseline.ps1" -CaptureRoot (Join-Path $TestRoot 'baseline') -OutputFile (Join-Path $TestRoot 'baseline_summary.csv')

Write-Host "`n--- Running Summarize Script (Optimization) ---"
& powershell -NoProfile -File "scripts/optimization/Summarize-VulkaniteBaseline.ps1" -CaptureRoot (Join-Path $TestRoot 'optimization') -OutputFile (Join-Path $TestRoot 'optimization_summary.csv')

Write-Host "`n--- Running Compare Script ---"
& powershell -NoProfile -File "scripts/optimization/Compare-VulkaniteOptimization.ps1" -BaselineRoot (Join-Path $TestRoot 'baseline') -OptimizationRoot (Join-Path $TestRoot 'optimization') -OutputFile (Join-Path $TestRoot 'comparison.csv')

Write-Host "`nTest complete. Cleaning up."
# Remove-Item -Path $TestRoot -Recurse -Force
