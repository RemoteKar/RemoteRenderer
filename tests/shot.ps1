# Captures the test client's window (by process id) to a PNG, even when other windows cover it (PrintWindow).
# A minimized window draws nothing and would be captured as whatever it showed last, so one is first brought back
# without being activated, behind everything else, and given a moment to draw.
param([int]$ProcessId, [string]$Out)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Cap {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
[Cap]::SetProcessDPIAware() | Out-Null
$p = Get-Process -Id $ProcessId -ErrorAction Stop
$h = $p.MainWindowHandle
if ($h -eq [IntPtr]::Zero) { throw "process $ProcessId has no main window" }
$was = [Cap]::IsIconic($h)
if ($was) {
    [Cap]::ShowWindow($h, 4) | Out-Null                                   # SW_SHOWNOACTIVATE
    [Cap]::SetWindowPos($h, [IntPtr]1, 0, 0, 0, 0, 0x13) | Out-Null       # HWND_BOTTOM, no move / size / activate
    Start-Sleep -Milliseconds 1200
}
$r = New-Object Cap+RECT
[Cap]::GetClientRect($h, [ref]$r) | Out-Null
$w = $r.R - $r.L; $hgt = $r.B - $r.T
if ($w -le 0 -or $hgt -le 0) { throw "the window has no size (minimized=$([Cap]::IsIconic($h)))" }
$bmp = New-Object System.Drawing.Bitmap $w, $hgt
$g = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $g.GetHdc()
[Cap]::PrintWindow($h, $dc, 3) | Out-Null   # PW_CLIENTONLY | PW_RENDERFULLCONTENT
$g.ReleaseHdc($dc)
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "SHOT $Out ${w}x$hgt$(if ($was) { ' (was minimized)' })"
