# Convenient launcher for Kernel Touch Mapper on Redmi K20 Pro

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) {
    $ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source
}

if (-not $ADB) {
    Write-Host "[-] Could not find adb.exe. Please install Android Platform Tools." -ForegroundColor Red
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " 🎮 Starting Kernel Touch Mapper for Redmi K20 Pro" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Check device
$device = & $ADB devices | Select-String "device$"
if (-not $device) {
    Write-Host "[-] No authorized device connected. Connect your phone and enable USB debugging." -ForegroundColor Red
    exit 1
}
Write-Host "[+] Device detected: $device" -ForegroundColor Green

# Setup forward
& $ADB forward tcp:8888 tcp:8888 | Out-Null
Write-Host "[+] Port forwarded (tcp:8888 -> tcp:8888)" -ForegroundColor Green

# Check if daemon is running on phone, if not start it
$running = & $ADB shell su -c "ps -A | grep touch_injector"
if (-not $running) {
    Write-Host "[*] Starting touch_injector daemon on device as root..." -ForegroundColor Yellow
    & $ADB shell su -c "nohup /data/local/tmp/touch_injector > /data/local/tmp/injector.log 2>&1 &"
    Start-Sleep -Milliseconds 500
}
Write-Host "[+] Device touch daemon is active!" -ForegroundColor Green

# Launch client
Write-Host "[*] Launching Windows Trajectory Engine..." -ForegroundColor Cyan
python "$PSScriptRoot\client\main.py"
