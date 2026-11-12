# Word로 DOCX를 PDF로 바꾼다 (제출 전 양식 모양 확인용).
# 사용: powershell -File scripts\windows\docx-to-pdf.ps1 -In C:\path\form.docx -Out C:\path\form.pdf
param([Parameter(Mandatory = $true)][string]$In, [Parameter(Mandatory = $true)][string]$Out)
$ErrorActionPreference = "Stop"
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $doc = $word.Documents.Open($In, $false, $true)
    $doc.SaveAs2($Out, 17)   # 17 = wdFormatPDF
    "pages=" + $doc.ComputeStatistics(2)
    $doc.Close($false)
} finally {
    $word.Quit()
}
