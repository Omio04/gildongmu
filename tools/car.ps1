# Drive the emulated car through the Virtual Console HTTP API.
#   .\car.ps1 -Gear Drive -Speed 0     # idling
#   .\car.ps1 -Gear Drive -Speed 30    # moving
#   .\car.ps1 -Gear Park               # parked
param(
    [ValidateSet("Park", "Reverse", "Neutral", "Drive")][string]$Gear = "Park",
    [int]$Speed = 0
)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb forward tcp:8088 tcp:8088 | Out-Null
$base = "http://127.0.0.1:8088"

if ($Gear -ne "Drive") { $Speed = 0 }
$accel = if ($Speed -gt 0) { 30 } else { 0 }
$brake = 0

Invoke-WebRequest -Uri "$base/vehicle/gear?gear=$Gear" -Method Put -UseBasicParsing | Out-Null
Invoke-WebRequest -Uri "$base/vehicle/controls?speed=$Speed&accelerator=$accel&brake=$brake&steering=0" -Method Put -UseBasicParsing | Out-Null
Start-Sleep -Seconds 2

$s = & $adb -s emulator-5554 shell "dumpsys car_service --services CarDrivingStateService" 2>&1 | Select-String "Current Driving State"
$u = & $adb -s emulator-5554 shell "dumpsys car_service --services CarUxRestrictionsManagerService" 2>&1 | Select-String "^Display id"
"gear=$Gear speed=$Speed -> " + $s.Line.Trim() + " | " + ($u.Line -replace 'time:.*', '').Trim()
