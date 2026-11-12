# 시연 영상 내레이션을 Windows 한국어 음성(Microsoft Heami)으로 만든다.
# 사용: powershell -File scripts\windows\tts-narration.ps1 -Dir <narration.txt가 있는 폴더>  → <Dir>\narr\NN.wav
param([string]$Dir)
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice("Microsoft Heami Desktop")
$s.Rate = 0
foreach ($line in [IO.File]::ReadAllLines("$Dir\narration.txt", [Text.Encoding]::UTF8)) {
  if ($line -notmatch "\|") { continue }
  $id, $text = $line.Split("|", 2)
  New-Item -ItemType Directory -Force -Path "$Dir\narr" | Out-Null
  $s.SetOutputToWaveFile("$Dir\narr\$id.wav")
  $s.Speak($text)
}
$s.SetOutputToNull()
"done"
