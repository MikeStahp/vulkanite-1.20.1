[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('static-exterior', 'camera-motion', 'emitter-interior', 'materials', 'volumetrics', 'entities', 'chunk-traversal', 'stress-entities', 'volumetric-room')]
    [string]$Scene,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 99)]
    [int]$Run,

    [ValidateRange(2, 3600)]
    [int]$DurationSeconds = 15,

    [ValidateRange(0, 600)]
    [int]$WarmupSeconds = 60,

    [int]$GameProcessId = 0,

    [ValidateSet('baseline', 'optimization')]
    [string]$Tag = 'baseline',

    [string]$Dimension = '',
    [Nullable[double]]$X = $null,
    [Nullable[double]]$Y = $null,
    [Nullable[double]]$Z = $null,
    [Nullable[double]]$Yaw = $null,
    [Nullable[double]]$Pitch = $null,
    [string]$WorldTime = '',
    [string]$Weather = '',

    [string]$OutputRoot = 'run/vulkanite-baselines'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repoRoot

$presentMon = 'C:\Program Files\NVIDIA Corporation\FrameViewSDK\bin\PresentMon_x64.exe'
if (-not (Test-Path -LiteralPath $presentMon)) {
    throw "PresentMon was not found at $presentMon"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    throw 'PresentMon requires an elevated PowerShell because the NVIDIA FrameView service is protected. Reopen PowerShell as Administrator in this repository and rerun this command.'
}

$frameViewService = Get-Service -Name FvSvc -ErrorAction Stop
if ($frameViewService.Status -ne 'Running') {
    Start-Service -Name FvSvc
    $frameViewService.WaitForStatus('Running', [TimeSpan]::FromSeconds(10))
}

if ($GameProcessId -le 0) {
    $game = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq 'java.exe' -and
            $_.CommandLine -match '(devlaunchinjector\.Main|KnotClient|net\.minecraft\.client\.main\.Main)'
        } |
        Sort-Object CreationDate -Descending |
        Select-Object -First 1
    if (-not $game) {
        throw 'No running Minecraft development client was found. Start ./gradlew runClient, enter the requested scene, and rerun this command.'
    }
    $GameProcessId = [int]$game.ProcessId
}

$gameProcess = Get-Process -Id $GameProcessId -ErrorAction Stop
$configFiles = [ordered]@{
    'options.txt' = 'run/options.txt'
    'iris.properties' = 'run/config/iris.properties'
    'sodium-options.json' = 'run/config/sodium-options.json'
    'vulkanite-dlss.properties' = 'run/config/vulkanite-dlss.properties'
    'dlss_config.json' = 'run/vulkanite/dlss_config.json'
    'VulkaniteRT.txt' = 'run/shaderpacks/VulkaniteRT.txt'
    'settings.glsl' = 'shaderpacks/VulkaniteRT/shaders/lib/rt/settings.glsl'
    'shaders.properties' = 'shaderpacks/VulkaniteRT/shaders/shaders.properties'
}

$hashLines = foreach ($entry in $configFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Required configuration file is missing: $($entry.Value)"
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.Value).Hash
    "$($entry.Value)=$hash"
}
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $settingsHash = [BitConverter]::ToString(
        $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($hashLines -join "`n")))
    ).Replace('-', '')
} finally {
    $sha.Dispose()
}

$outputRootPath = if ([IO.Path]::IsPathRooted($OutputRoot)) {
    $OutputRoot
} else {
    Join-Path $repoRoot $OutputRoot
}
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$captureId = "$timestamp-$Scene-run$Run"
$captureDir = Join-Path (Join-Path $outputRootPath $Tag) $captureId
$configDir = Join-Path $captureDir 'config'
New-Item -ItemType Directory -Path $configDir -Force | Out-Null

foreach ($entry in $configFiles.GetEnumerator()) {
    Copy-Item -LiteralPath $entry.Value -Destination (Join-Path $configDir $entry.Key)
}
$hashLines | Set-Content -LiteralPath (Join-Path $captureDir 'config-sha256.txt') -Encoding UTF8

# --- GPU thermal guard ---
$gpuGuard = [ordered]@{ passed = $true; warnings = @() }
try {
    $smiLine = (& nvidia-smi --query-gpu=pstate,temperature.gpu,power.draw,power.limit --format=csv,noheader,nounits 2>$null).Trim()
    if ($smiLine) {
        $parts = $smiLine -split ',\s*'
        $pstate = $parts[0]  # e.g. 'P0'
        $gpuTemp = [double]$parts[1]
        $powerDraw = [double]$parts[2]
        $powerLimit = [double]$parts[3]
        $gpuGuard.pstate = $pstate
        $gpuGuard.temperature_c = $gpuTemp
        $gpuGuard.power_draw_w = $powerDraw
        $gpuGuard.power_limit_w = $powerLimit

        $pstateNum = if ($pstate -match 'P(\d+)') { [int]$Matches[1] } else { 0 }
        if ($pstateNum -ge 3 -and $WarmupSeconds -eq 0) {
            $gpuGuard.warnings += "GPU is in $pstate at capture start (no warmup); results may reflect cold clocks"
            $gpuGuard.passed = $false
        }
        if ($gpuTemp -ge 85) {
            $gpuGuard.warnings += "GPU temperature is ${gpuTemp}C (>= 85C); thermal throttling likely"
            $gpuGuard.passed = $false
        }
        if ($powerLimit -gt 0 -and $powerDraw -ge ($powerLimit * 0.95)) {
            $gpuGuard.warnings += "GPU power draw ${powerDraw}W is >= 95% of limit ${powerLimit}W; power throttling likely"
            $gpuGuard.passed = $false
        }
        if (-not $gpuGuard.passed) {
            foreach ($w in $gpuGuard.warnings) { Write-Warning $w }
        }
    }
} catch {
    $gpuGuard.warnings += "nvidia-smi query failed: $($_.Exception.Message)"
    $gpuGuard.passed = $false
}

# --- Capture VRAM before ---
$vramBeforeMib = $null
try {
    $vramLine = (& nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits 2>$null).Trim()
    if ($vramLine) { $vramBeforeMib = [double]$vramLine }
} catch { }

$gitCommit = (git rev-parse HEAD).Trim()
$gitStatus = git status --short
$manifest = [ordered]@{
    capture_id = $captureId
    tag = $Tag
    captured_at = (Get-Date).ToString('o')
    git_commit = $gitCommit
    dirty_status = @($gitStatus)
    settings_hash = $settingsHash
    scene = $Scene
    run = $Run
    duration_seconds = $DurationSeconds
    warmup_seconds = $WarmupSeconds
    process_id = $GameProcessId
    process_start = $gameProcess.StartTime.ToString('o')
    dimension = $Dimension
    position = @($X, $Y, $Z)
    rotation = @($Yaw, $Pitch)
    world_time = $WorldTime
    weather = $Weather
    presentmon = $presentMon
    frameview_service = 'FvSvc'
    vsync = $false
    fps_cap = 260
    gpu_guard = $gpuGuard
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $captureDir 'manifest.json') -Encoding UTF8

if ($WarmupSeconds -gt 0) {
    Write-Host "Warming $Scene for $WarmupSeconds seconds. Keep the camera/workload on the declared path."
    Start-Sleep -Seconds $WarmupSeconds
}

$telemetryFile = Join-Path $captureDir 'nvidia-smi.csv'
$telemetryJob = Start-Job -ArgumentList $telemetryFile, ($DurationSeconds + 2) -ScriptBlock {
    param($path, $seconds)
    'timestamp,name,driver_version,pstate,gpu_util_pct,memory_util_pct,graphics_clock_mhz,memory_clock_mhz,temp_c,power_w,power_limit_w,vram_used_mib,vram_total_mib' |
        Set-Content -LiteralPath $path -Encoding UTF8
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $seconds) {
        & nvidia-smi --query-gpu=timestamp,name,driver_version,pstate,utilization.gpu,utilization.memory,clocks.current.graphics,clocks.current.memory,temperature.gpu,power.draw,power.limit,memory.used,memory.total --format=csv,noheader,nounits |
            Add-Content -LiteralPath $path -Encoding UTF8
        Start-Sleep -Seconds 1
    }
}

$presentMonFile = Join-Path $captureDir 'presentmon.csv'
$sessionName = "Vulkanite-$($GameProcessId)-$Run"
try {
    & $presentMon `
        --process_id $GameProcessId `
        --timed $DurationSeconds `
        --terminate_after_timed `
        --output_file $presentMonFile `
        --qpc_time_ms `
        --exclude_dropped `
        --no_console_stats `
        --session_name $sessionName
    if ($LASTEXITCODE -ne 0) {
        throw "PresentMon exited with code $LASTEXITCODE"
    }
} finally {
    Wait-Job -Job $telemetryJob -Timeout ($DurationSeconds + 10) | Out-Null
    Receive-Job -Job $telemetryJob -ErrorAction SilentlyContinue | Out-Null
    Remove-Job -Job $telemetryJob -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath $presentMonFile)) {
    throw 'PresentMon returned success but produced no CSV. The capture is invalid; verify the FrameView service and elevated session.'
}
$lineCount = (Get-Content -LiteralPath $presentMonFile | Measure-Object -Line).Lines
if ($lineCount -lt 3) {
    throw "PresentMon produced only $lineCount CSV lines. The capture is invalid."
}

# --- Frame count validation (acceptance spec requires >= 120 frames) ---
$frameRows = @(Import-Csv -LiteralPath $presentMonFile)
$frameCountValidated = $frameRows.Count -ge 120
if (-not $frameCountValidated) {
    Write-Warning "Only $($frameRows.Count) frames captured (minimum 120 required for acceptance). Capture is flagged."
}

# --- Outlier detection via IQR ---
$outlierWarning = $false
$outlierPct = 0.0
$columns = @($frameRows[0].PSObject.Properties.Name)
$cpuCol = @('CPUFrameTime', 'msBetweenPresents') | Where-Object { $columns -contains $_ } | Select-Object -First 1
if ($cpuCol -and $frameRows.Count -ge 10) {
    [double[]]$frameTimes = @($frameRows | ForEach-Object { [double]($_.$cpuCol) })
    $sorted = @($frameTimes | Sort-Object)
    $q1Idx = [math]::Floor($sorted.Count * 0.25)
    $q3Idx = [math]::Floor($sorted.Count * 0.75)
    $q1 = $sorted[$q1Idx]
    $q3 = $sorted[$q3Idx]
    $iqr = $q3 - $q1
    $lowerFence = $q1 - 1.5 * $iqr
    $upperFence = $q3 + 1.5 * $iqr
    $outlierCount = ($frameTimes | Where-Object { $_ -lt $lowerFence -or $_ -gt $upperFence }).Count
    $outlierPct = [math]::Round(100.0 * $outlierCount / $frameTimes.Count, 2)
    if ($outlierPct -gt 5.0) {
        $outlierWarning = $true
        Write-Warning "$outlierPct% of frames are IQR outliers ($outlierCount / $($frameTimes.Count)). Background interference or thermal throttling likely."
    }
}

# --- Capture VRAM after ---
$vramAfterMib = $null
try {
    $vramLine = (& nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits 2>$null).Trim()
    if ($vramLine) { $vramAfterMib = [double]$vramLine }
} catch { }

$processAfter = Get-Process -Id $GameProcessId -ErrorAction SilentlyContinue
[ordered]@{
    working_set_mib = if ($processAfter) { [math]::Round($processAfter.WorkingSet64 / 1MB, 3) } else { $null }
    private_memory_mib = if ($processAfter) { [math]::Round($processAfter.PrivateMemorySize64 / 1MB, 3) } else { $null }
    captured_csv_lines = $lineCount
    frame_count_validated = $frameCountValidated
    outlier_warning = $outlierWarning
    outlier_pct = $outlierPct
    vram_before_mib = $vramBeforeMib
    vram_after_mib = $vramAfterMib
    vram_delta_mib = if ($null -ne $vramBeforeMib -and $null -ne $vramAfterMib) { [math]::Round($vramAfterMib - $vramBeforeMib, 3) } else { $null }
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $captureDir 'process-memory.json') -Encoding UTF8

# --- Update manifest with post-capture validation ---
$manifest.frame_count_validated = $frameCountValidated
$manifest.outlier_warning = $outlierWarning
$manifest.outlier_pct = $outlierPct
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $captureDir 'manifest.json') -Encoding UTF8

Write-Host "Capture complete: $captureDir"
Write-Host "Tag: $Tag"
Write-Host "Settings hash: $settingsHash"
Write-Host "Frames: $($frameRows.Count) (validated: $frameCountValidated)"
if ($outlierWarning) {
    Write-Host "WARNING: $outlierPct% outlier frames detected" -ForegroundColor Yellow
}
if (-not $gpuGuard.passed) {
    Write-Host 'WARNING: GPU thermal guard flagged issues (see manifest)' -ForegroundColor Yellow
}
Write-Host 'Run scripts/optimization/Summarize-VulkaniteBaseline.ps1 to validate and aggregate captures.'
