<#
.SYNOPSIS
    Automated multi-scene capture orchestrator for Vulkanite optimization testing.
.DESCRIPTION
    Iterates over all defined scenes and automatically captures them. It prompts the
    user to run the corresponding Minecraft command before proceeding with the capture
    for that scene.
.EXAMPLE
    ./scripts/optimization/Capture-VulkaniteAllScenes.ps1 -Tag optimization
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 99)]
    [int]$RunsPerScene = 3,

    [ValidateRange(2, 3600)]
    [int]$DurationSeconds = 15,

    [ValidateRange(0, 600)]
    [int]$WarmupSeconds = 60,

    [ValidateSet('baseline', 'optimization')]
    [string]$Tag = 'baseline',

    [string]$OutputRoot = 'run/vulkanite-baselines'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $repoRoot

$game = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -eq 'java.exe' -and
        $_.CommandLine -match '(devlaunchinjector\.Main|KnotClient|net\.minecraft\.client\.main\.Main)'
    } |
    Sort-Object CreationDate -Descending |
    Select-Object -First 1

if (-not $game) {
    throw 'No running Minecraft development client was found. Start ./gradlew runClient, enter the test world, and rerun this command.'
}
$GameProcessId = [int]$game.ProcessId

$scenes = [ordered]@{
    'static-exterior' = '/function vulkanite_benchmark:camera/outdoor'
    'camera-motion' = 'manual camera motion needed'
    'emitter-interior' = '/function vulkanite_benchmark:camera/indoor_lit'
    'materials' = '/function vulkanite_benchmark:camera/materials'
    'volumetrics' = '/function vulkanite_benchmark:camera/volumetrics'
    'entities' = '/function vulkanite_benchmark:camera/entities'
    'chunk-traversal' = '/function vulkanite_benchmark:camera/traversal_start'
    'stress-entities' = '/function vulkanite_benchmark:camera/stress_entities'
    'volumetric-room' = '/function vulkanite_benchmark:camera/volumetric_room'
}

Write-Host "=== Vulkanite Automated Capture Orchestrator ===" -ForegroundColor Cyan
Write-Host "Tag: $Tag"
Write-Host "Runs per scene: $RunsPerScene"
Write-Host "Duration: ${DurationSeconds}s"
Write-Host "Warmup: ${WarmupSeconds}s"
Write-Host ""

# Setup Windows API for input simulation
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Win32 {
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@

$mcProcess = Get-Process -Id $GameProcessId
if (-not $mcProcess.MainWindowHandle -or $mcProcess.MainWindowHandle -eq [IntPtr]::Zero) {
    Write-Warning "Could not find a valid Minecraft window to automate. Make sure the game is focused and not minimized."
} else {
    Write-Host "Minecraft window found. Automation starting in 3 seconds. DO NOT TOUCH YOUR MOUSE OR KEYBOARD." -ForegroundColor Yellow
    Start-Sleep -Seconds 3
}

foreach ($scene in $scenes.GetEnumerator()) {
    $sceneName = $scene.Key
    $command = $scene.Value

    Write-Host "--------------------------------------------------------"
    Write-Host "Automating scene: $sceneName" -ForegroundColor Cyan
    
    if ($mcProcess.MainWindowHandle -ne [IntPtr]::Zero) {
        [Win32]::ShowWindow($mcProcess.MainWindowHandle, 9) | Out-Null # SW_RESTORE
        [Win32]::SetForegroundWindow($mcProcess.MainWindowHandle) | Out-Null
        Start-Sleep -Milliseconds 1000
        
        # Unpause the game (which auto-pauses when losing focus)
        [System.Windows.Forms.SendKeys]::SendWait("{ESC}")
        Start-Sleep -Milliseconds 500
        
        # Open chat and type command
        [System.Windows.Forms.SendKeys]::SendWait("t")
        Start-Sleep -Milliseconds 250
        [System.Windows.Forms.SendKeys]::SendWait("$command{ENTER}")
        Start-Sleep -Milliseconds 500
    } else {
        Write-Warning "Failed to bring Minecraft to foreground. Ensure it is not minimized."
    }
    
    for ($run = 1; $run -le $RunsPerScene; $run++) {
        Write-Host "Running capture $run of $RunsPerScene for $sceneName..."
        
        $captureArgs = @(
            "-Scene", $sceneName,
            "-Run", $run,
            "-DurationSeconds", $DurationSeconds,
            "-WarmupSeconds", $WarmupSeconds,
            "-GameProcessId", $GameProcessId,
            "-Tag", $Tag,
            "-OutputRoot", $OutputRoot
        )
        
        & powershell -NoProfile -File "scripts/optimization/Capture-VulkaniteBaseline.ps1" @captureArgs
        
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Capture script exited with error code $LASTEXITCODE"
        }
    }
}

Write-Host "--------------------------------------------------------"
Write-Host "All scenes complete. Generating summary..." -ForegroundColor Cyan

& powershell -NoProfile -File "scripts/optimization/Summarize-VulkaniteBaseline.ps1" -CaptureRoot $OutputRoot

Write-Host "Orchestrator finished." -ForegroundColor Green
