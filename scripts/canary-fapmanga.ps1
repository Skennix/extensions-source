#requires -Version 5.1
<#
.SYNOPSIS
    Canary FapManga — dò site thật theo đúng mẫu URL/host mà extension đã khoá (T4-T6 plan),
    chạy trên máy user (KHÔNG chạy trên CI — datacenter IP đã đo bị site chặn).

.MÔ TẢ
    Gọi lần lượt 4 URL đúng nhịp HTML_RATE (1 req/2s, khớp hằng số HTML_RATE_PERMITS/PERIOD trong
    Fapmanga.kt):
      1. Trang danh sách  https://fapmanga.com/danh-sach
      2. Trang bộ truyện  — link 1-tầng ĐẦU TIÊN khớp mẫu ^/truyen-hentai/[^/]+/?$ lấy được từ (1)
      3. Trang chương     — link 2-tầng ĐẦU TIÊN khớp mẫu ^/truyen-hentai/<slug>/[^/]+/?$ lấy từ (2)
      4. Một ảnh CDN      — <img src> ĐẦU TIÊN host cdn.fapmanga.com lấy từ (3)
    Không hardcode slug — site đổi nội dung liên tục (đúng như 3 hàm parse thuần trong
    FapmangaParsers.kt không hardcode gì).

    Đỏ  (bất kỳ bước nào lỗi/rỗng/nhịp gãy) => exit != 0, ghi canary-fail.log, bắn thông báo
        Windows (BurntToast nếu có > msg.exe > Event Log Application — đã đo trên máy này 2026-08-17:
        không có BurntToast, msg.exe chạy được exit 0, EventLog "Application" ghi được không cần
        quyền admin).
    Xanh => ghi một dòng vào canary-ok.log: timestamp + 4 con số (listCount, boCount, chuongCount,
        imageHttpStatus).

    Quy trình khi canary đỏ (hợp đồng, chép từ spec §6.2 / plan Task 9): chụp fixture mới => viết
    test đỏ theo fixture đó => sửa parser => cả hai xanh.
#>

[CmdletBinding()]
param(
    # Cho phép trỏ tạm sang URL khác để tái hiện ca "bắt gãy" (test 9.2) mà không phải sửa script.
    [string]$ListPath = '/danh-sach'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Hằng số — một chỗ, khớp Fapmanga.kt (HTML_RATE_PERMITS=1 / HTML_RATE_PERIOD=2s).
# Đề xuất, chưa đo lại riêng cho canary — chỉnh ở đây nếu extension đổi nhịp.
# ---------------------------------------------------------------------------
$BaseUrl = 'https://fapmanga.com'
$HtmlRateSeconds = 2
# Chuỗi UA trình duyệt thật — site chặn IP datacenter chứ không chặn UA (đã đo, phép đo #RESEARCH),
# nhưng cứ lịch sự, không giả danh bot/script.
$UserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OkLog = Join-Path $ScriptDir 'canary-ok.log'
$FailLog = Join-Path $ScriptDir 'canary-fail.log'

# Regex khớp ĐÚNG mẫu đã khoá trong FapmangaParsers.kt — không nới ra "mọi <a>".
$MangaUrlRegex = [regex]"^$([regex]::Escape($BaseUrl))/truyen-hentai/[^/?#]+/?$"
$CdnHost = 'cdn.fapmanga.com'

# ---------------------------------------------------------------------------
# Đo nhịp: ghi lại thời điểm request HTML trước đó, sleep đủ để khoảng cách >= $HtmlRateSeconds,
# rồi TỰ KIỂM khoảng cách thật đo được (test 9.3 — không tin tưởng mù vào Start-Sleep).
# ---------------------------------------------------------------------------
$script:LastHtmlRequestAt = $null
$script:MeasuredGapsMs = [System.Collections.Generic.List[double]]::new()

function Invoke-HtmlPage {
    param([Parameter(Mandatory)][string]$Url)

    if ($script:LastHtmlRequestAt) {
        $elapsedMs = ((Get-Date) - $script:LastHtmlRequestAt).TotalMilliseconds
        $remainingMs = ($HtmlRateSeconds * 1000) - $elapsedMs
        if ($remainingMs -gt 0) {
            Start-Sleep -Milliseconds ([int]$remainingMs)
        }
    }

    $requestStart = Get-Date
    if ($script:LastHtmlRequestAt) {
        $gapMs = ($requestStart - $script:LastHtmlRequestAt).TotalMilliseconds
        $script:MeasuredGapsMs.Add($gapMs)
        Write-Host ("[nhip] gap since previous HTML request: {0:F0} ms" -f $gapMs)
    }
    $script:LastHtmlRequestAt = $requestStart

    Write-Host "[GET] $Url"
    $response = Invoke-WebRequest -Uri $Url -UserAgent $UserAgent -UseBasicParsing -TimeoutSec 30
    if ($response.StatusCode -ne 200) {
        throw "HTTP $($response.StatusCode) tu $Url"
    }
    return $response.Content
}

function Get-FirstAbsoluteHref {
    <#
        Trích mọi giá trị href thô từ HTML (không cần DOM parser đầy đủ — canary chỉ cần "có match
        hay không" và "lấy link đầu tiên", không cắt phạm vi khối như extension thật), tuyệt đối
        hoá theo BaseUrl, rồi lọc theo regex truyền vào. Trả về (count, firstAbsoluteUrl-hoặc-null).
    #>
    param(
        [Parameter(Mandatory)][string]$Html,
        [Parameter(Mandatory)][regex]$Pattern
    )

    $hrefMatches = [regex]::Matches($Html, 'href\s*=\s*"([^"]+)"')
    $absoluteUrls = foreach ($m in $hrefMatches) {
        $raw = $m.Groups[1].Value
        if ($raw -match '^https?://') {
            $raw
        } elseif ($raw.StartsWith('/')) {
            "$BaseUrl$raw"
        }
    }
    $matched = $absoluteUrls | Where-Object { $_ -and $Pattern.IsMatch($_) }
    return [pscustomobject]@{
        Count = ($matched | Measure-Object).Count
        First = ($matched | Select-Object -First 1)
    }
}

function Get-FirstCdnImageUrl {
    param([Parameter(Mandatory)][string]$Html)

    $srcMatches = [regex]::Matches($Html, '<img[^>]+src\s*=\s*"([^"]+)"')
    $cdnUrls = foreach ($m in $srcMatches) {
        $raw = $m.Groups[1].Value
        try {
            $uri = [uri]$raw
            if ($uri.IsAbsoluteUri -and $uri.Host -eq $CdnHost) { $raw }
        } catch {
            # URI không hợp lệ (VD "data:image/..."), bỏ qua êm — không làm sập script.
        }
    }
    return [pscustomobject]@{
        Count = ($cdnUrls | Measure-Object).Count
        First = ($cdnUrls | Select-Object -First 1)
    }
}

function Send-CanaryAlert {
    <#
        Chọn đường ĐƠN GIẢN nhất chạy được, đo thử trên máy này 2026-08-17:
        BurntToast không cài => msg.exe chạy được (exit 0) => dùng msg.exe, dự phòng Event Log.
    #>
    param([Parameter(Mandatory)][string]$Message)

    if (Get-Module -ListAvailable -Name BurntToast) {
        try {
            Import-Module BurntToast -ErrorAction Stop
            New-BurntToastNotification -Text 'FapManga canary DO', $Message
            Write-Host '[alert] da bao qua BurntToast'
            return
        } catch {
            Write-Host "[alert] BurntToast loi: $($_.Exception.Message)"
        }
    }

    try {
        & msg.exe $env:USERNAME /TIME:30 "FapManga canary DO: $Message" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host '[alert] da bao qua msg.exe'
            return
        }
        Write-Host "[alert] msg.exe tra ve exit code $LASTEXITCODE"
    } catch {
        Write-Host "[alert] msg.exe loi: $($_.Exception.Message)"
    }

    try {
        Write-EventLog -LogName Application -Source 'Application' -EventId 1 -EntryType Warning `
            -Message "FapManga canary DO: $Message"
        Write-Host '[alert] da ghi Event Log Application'
    } catch {
        Write-Host "[alert] Event Log loi: $($_.Exception.Message)"
    }
}

function Write-FailLog {
    param([Parameter(Mandatory)][string]$Reason)
    $line = "{0} FAIL: {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Reason
    Add-Content -Path $FailLog -Value $line
}

# ---------------------------------------------------------------------------
# Chạy chính
# ---------------------------------------------------------------------------
try {
    # 1) Trang danh sách
    $listHtml = Invoke-HtmlPage -Url "$BaseUrl$ListPath"
    $listResult = Get-FirstAbsoluteHref -Html $listHtml -Pattern $MangaUrlRegex
    if ($listResult.Count -le 0) {
        throw "danh-sach: khong tim thay link truyen-hentai (mau ^$BaseUrl/truyen-hentai/...) tu $BaseUrl$ListPath"
    }
    $mangaUrl = $listResult.First
    Write-Output "[danh-sach] match=$($listResult.Count) first=$mangaUrl"

    # 2) Trang bộ truyện — regex chương phải neo đúng slug của bộ này (khớp cách extension làm).
    $slug = ($mangaUrl -replace '.*truyen-hentai/', '') -replace '/.*', ''
    $chapterUrlRegex = [regex]"^$([regex]::Escape($BaseUrl))/truyen-hentai/$([regex]::Escape($slug))/[^/?#]+/?$"

    $boHtml = Invoke-HtmlPage -Url $mangaUrl
    $boResult = Get-FirstAbsoluteHref -Html $boHtml -Pattern $chapterUrlRegex
    if ($boResult.Count -le 0) {
        throw "bo-truyen: khong tim thay link chuong (mau 2-tang) tu $mangaUrl"
    }
    $chapterUrl = $boResult.First
    Write-Output "[bo-truyen] match=$($boResult.Count) first=$chapterUrl"

    # 3) Trang chương
    $chuongHtml = Invoke-HtmlPage -Url $chapterUrl
    $imgResult = Get-FirstCdnImageUrl -Html $chuongHtml
    if ($imgResult.Count -le 0) {
        throw "chuong: khong tim thay anh host $CdnHost tu $chapterUrl"
    }
    $imageUrl = $imgResult.First
    Write-Output "[chuong] match=$($imgResult.Count) first=$imageUrl"

    # 4) Một ảnh CDN — không tính vào nhịp HTML_RATE (host khác, nhịp CDN riêng trong extension).
    Write-Output "[GET-img] $imageUrl"
    $imageResponse = Invoke-WebRequest -Uri $imageUrl -UserAgent $UserAgent -UseBasicParsing -TimeoutSec 30
    $contentType = $imageResponse.Headers['Content-Type']
    if ($contentType -is [array]) { $contentType = $contentType[0] }
    if ($imageResponse.StatusCode -ne 200 -or $contentType -notmatch '^image/') {
        throw "anh CDN: HTTP $($imageResponse.StatusCode), Content-Type='$contentType' tu $imageUrl"
    }
    Write-Output "[anh-cdn] status=$($imageResponse.StatusCode) content-type=$contentType"

    # 9.3 — tự kiểm nhịp: MỌI khoảng cách đo được giữa 2 request HTML liên tiếp phải >= nguong.
    # Ngưỡng trừ hao 100ms cho sai số lập lịch của Windows (Start-Sleep không chính xác tuyệt đối
    # tới mili-giây) — vẫn đủ chặt để bắt ca "quên sleep" (chênh lệch cỡ giây, không phải mili-giây).
    $thresholdMs = ($HtmlRateSeconds * 1000) - 100
    $tooFast = $script:MeasuredGapsMs | Where-Object { $_ -lt $thresholdMs }
    if ($tooFast) {
        $gapsText = ($script:MeasuredGapsMs -join ', ')
        throw "nhip HTML vi pham: can >= ${thresholdMs}ms, do duoc [$gapsText] ms"
    }
    Write-Output ("[nhip] tat ca khoang cach hop le (>= {0} ms): [{1}]" -f $thresholdMs, ($script:MeasuredGapsMs -join ', '))

    $okLine = "{0} OK list={1} bo={2} chuong={3} img_status={4}" -f `
        (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $listResult.Count, $boResult.Count, $imgResult.Count, $imageResponse.StatusCode
    Add-Content -Path $OkLog -Value $okLine
    Write-Output $okLine
    exit 0
} catch {
    $reason = $_.Exception.Message
    Write-FailLog -Reason $reason
    Send-CanaryAlert -Message $reason
    if ($env:CANARY_DEBUG) { Write-Output $_.ScriptStackTrace }
    Write-Error "Canary FapManga DO: $reason"
    exit 1
}
