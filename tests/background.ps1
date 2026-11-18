# Waits for a process's main window and takes it out of the way: if it grabbed the foreground it is minimized (which
# hands the focus back to whatever had it) and shown again without being activated, behind everything else.
# The test client is only ever screenshotted (PrintWindow works on a covered window) and driven by console commands.
param([int]$ProcessId, [int]$Seconds = 60, [string]$Hold = '')
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Bg {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
$end = (Get-Date).AddSeconds($Seconds)
$done = 0
while ((Get-Date) -lt $end) {
    $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $p) { break }
    # a test that needs a real key press has the window for a moment (input.ps1 tap:<key>)
    if ($Hold -and (Test-Path $Hold)) { Start-Sleep -Milliseconds 100; continue }
    $h = $p.MainWindowHandle
    if ($h -ne [IntPtr]::Zero) {
        if ([Bg]::GetForegroundWindow() -eq $h) {
            [Bg]::ShowWindow($h, 6) | Out-Null   # SW_MINIMIZE: the focus goes back to the window before
            Start-Sleep -Milliseconds 150
            [Bg]::ShowWindow($h, 4) | Out-Null   # SW_SHOWNOACTIVATE
            $done++
        }
        [Bg]::SetWindowPos($h, [IntPtr]1, 0, 0, 0, 0, 0x13) | Out-Null   # HWND_BOTTOM, no move / size / activate
    }
    Start-Sleep -Milliseconds 250
}
Write-Output "BACKGROUND handed the focus back $done time(s)"
