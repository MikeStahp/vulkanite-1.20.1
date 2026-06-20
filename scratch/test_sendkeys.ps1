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

$game = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -eq 'java.exe' -and
        $_.CommandLine -match '(devlaunchinjector\.Main|KnotClient|net\.minecraft\.client\.main\.Main)'
    } |
    Select-Object -First 1

if ($game) {
    $process = Get-Process -Id $game.ProcessId
    if ($process.MainWindowHandle -ne [IntPtr]::Zero) {
        Write-Host "Activating Minecraft window..."
        [Win32]::ShowWindow($process.MainWindowHandle, 9) # SW_RESTORE
        [Win32]::SetForegroundWindow($process.MainWindowHandle)
        Start-Sleep -Seconds 1
        
        Write-Host "Sending 't'..."
        [System.Windows.Forms.SendKeys]::SendWait("t")
        Start-Sleep -Milliseconds 500
        
        Write-Host "Sending '/help'..."
        [System.Windows.Forms.SendKeys]::SendWait("/help{ENTER}")
        Write-Host "Done."
    } else {
        Write-Host "No main window handle found."
    }
} else {
    Write-Host "Minecraft not running."
}
