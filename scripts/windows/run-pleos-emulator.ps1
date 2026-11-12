# AVD "PleosConnect"를 cold boot로 실행한다 (quick boot면 VHAL을 쓸 수 없다).
# 하드웨어 가속: Windows 기능 "Windows 하이퍼바이저 플랫폼"(WHPX)이 켜져 있어야 한다.
#   확인: & "$SdkRoot\emulator\emulator.exe" -accel-check  →  "WHPX ... is installed and usable."
#
# 사용 (PowerShell):
#   .\scripts\windows\run-pleos-emulator.ps1
#   .\scripts\windows\run-pleos-emulator.ps1 -SdkRoot D:\Android\Sdk -Gpu swiftshader_indirect
param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$AvdName = "PleosConnect",
    [string]$Gpu = "auto",
    [string]$LogFile = "$env:TEMP\pleos-emulator.log"
)
$ErrorActionPreference = "Stop"
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_HOME = $SdkRoot

$emulator = "$SdkRoot\emulator\emulator.exe"
$emuArgs = @("-avd", $AvdName, "-no-snapshot", "-no-boot-anim", "-gpu", $Gpu, "-netdelay", "none", "-netspeed", "full")
Start-Process -FilePath $emulator -ArgumentList $emuArgs -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err" -WindowStyle Normal
Write-Host "에뮬레이터를 시작했습니다. 로그: $LogFile"
Write-Host "부팅 확인: & `"$SdkRoot\platform-tools\adb.exe`" wait-for-device shell getprop sys.boot_completed"
Write-Host "Virtual Console: adb forward tcp:8088 tcp:8088 후 브라우저에서 http://127.0.0.1:8088/"
