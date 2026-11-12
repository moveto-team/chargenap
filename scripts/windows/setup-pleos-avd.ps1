# Pleos Connect Emulator(v2.1.0, API 34) AVD "PleosConnect"를 Windows에 만든다.
# Android Studio의 Device Manager에서 만드는 것과 같은 설정(2560x1440, 160dpi, cold boot)을 명령줄로 만든다.
#
# 사전 준비: Android Studio SDK Manager > SDK Update Sites에
#   https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml
# 을 추가하고 "Pleos Connect v2.1.0 APIs" 시스템 이미지를 설치한다.
# (이미지 사용은 Pleos Connect SDK 라이선스 동의를 뜻한다.)
#
# 사용 (PowerShell):
#   .\scripts\windows\setup-pleos-avd.ps1                       # SDK가 %LOCALAPPDATA%\Android\Sdk에 있을 때
#   .\scripts\windows\setup-pleos-avd.ps1 -SdkRoot D:\Android\Sdk -RamMb 4096
param(
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [int]$RamMb = 4096,
    [string]$AvdName = "PleosConnect"
)
$ErrorActionPreference = "Stop"

$imgRel = "system-images\android-34\pleos_car_emulator\x86_64"
if (-not (Test-Path "$SdkRoot\$imgRel\system.img")) {
    throw "Pleos 시스템 이미지가 없습니다: $SdkRoot\$imgRel (SDK Manager로 먼저 설치하세요)"
}
if (-not (Test-Path "$SdkRoot\emulator\emulator.exe")) {
    throw "Android Emulator가 없습니다: $SdkRoot\emulator (SDK Manager > SDK Tools > Android Emulator)"
}

$avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "$env:USERPROFILE\.android\avd" }
$avdDir = Join-Path $avdHome "$AvdName.avd"
New-Item -ItemType Directory -Force -Path $avdDir | Out-Null

@"
avd.ini.encoding=UTF-8
path=$avdDir
path.rel=avd\$AvdName.avd
target=android-34
"@ | Set-Content -Encoding ASCII (Join-Path $avdHome "$AvdName.ini")

# 값은 시스템 이미지의 devices.xml(Pleos Connect v2.1.0 기기 정의)을 따른다.
@"
AvdId=$AvdName
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=Pleos Connect v2.1.0
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
hw.accelerometer=no
hw.audioInput=yes
hw.audioOutput=yes
hw.battery=no
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=42dot
hw.device.name=Pleos_Connect_v2_0
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.initialOrientation=landscape
hw.keyboard=yes
hw.lcd.density=160
hw.lcd.height=1440
hw.lcd.width=2560
hw.mainKeys=no
hw.ramSize=$RamMb
hw.sdCard=no
hw.sensors.orientation=no
hw.sensors.proximity=no
hw.trackBall=no
image.sysdir.1=$imgRel\
showDeviceFrame=no
tag.display=Pleos Connect v2.1.0 APIs
tag.id=android-automotive
vm.heapSize=256
"@ | Set-Content -Encoding ASCII (Join-Path $avdDir "config.ini")

Write-Host "AVD '$AvdName' 생성 완료 ($avdDir, RAM ${RamMb}MB)"
Write-Host "실행: .\scripts\windows\run-pleos-emulator.ps1 -SdkRoot `"$SdkRoot`""
