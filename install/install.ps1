# DB-Robot R1 - bo cai cho Windows.
#
#   Cach dung:  bam dup vao "Cai-dat-DB-Robot.bat" (cung thu muc voi tep nay)
#   Hoac dan dong sau vao PowerShell:
#     irm https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.ps1 | iex
#
# Script tai ban DB-Robot moi nhat, ket noi toi loa Phicomm R1 qua adb tren Wi-Fi va cai vao loa.
# Chi can lam mot lan: cac ban sau cap nhat ngay trong trang dieu khien cua loa (http://IP-loa:8088).
#
# Chu thich va thong bao viet khong dau co chu y: Windows PowerShell 5.1 doc tep .ps1 khong co BOM
# theo bang ma ANSI, nen chu co dau se thanh ky tu rac tren nhieu may.
param([string]$Ip = "")

$Repo     = "ledienbien-ai/db-robot-phincomR1"
$ApkUrl   = "https://github.com/$Repo/releases/latest/download/DB-Robot-R1.apk"
$ToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
$Pkg      = "vn.dbrobot.r1"
$Activity = "$Pkg/info.dourok.voicebot.MainActivity"
$Port     = 5555
$DevApk   = "/data/local/tmp/dbrobot.apk"
$DevLog   = "/data/local/tmp/dbrobot-cmd.log"
# Ung dung tro ly khac dung chung micro voi DB-Robot (AI Box Plus va ban xiaozhi goc).
$Rivals   = @("info.dourok.voicebot", "info.dourok.voicebot.dev")

$script:Adb    = "adb"
$script:Serial = ""

function Say([string]$Text)  { Write-Host $Text }
function Step([string]$Text) { Write-Host ""; Write-Host "==> $Text" -ForegroundColor Cyan }

# Cau hoi co/khong; $Default la cau tra loi khi chi bam Enter ("c" hoac "k").
function Ask-YesNo([string]$Question, [string]$Default) {
    $r = Read-Host $Question
    if ([string]::IsNullOrWhiteSpace($r)) { $r = $Default }
    return ($r -match '^[cCyY]')
}

# Chay adb va lay ket qua. Dung System.Diagnostics.Process thay cho "& adb ... 2>&1": tren
# PowerShell 5.1 moi dong adb ghi ra stderr bi bien thanh mot loi cua PowerShell.
function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSec = 60) {
    $quoted = foreach ($a in $Arguments) { if ($a -match '[\s;&|()<>]') { '"' + $a + '"' } else { $a } }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:Adb
    $psi.Arguments = ($quoted -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    try { [void]$p.Start() } catch { return @{ Out = ""; Code = -1 } }
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        try { $p.Kill() } catch { }
        return @{ Out = ""; Code = -2 }
    }
    # Bounded wait for the pipes too: a process that outlives adb and still holds them (the adb
    # server, if this call happened to start it) would otherwise keep ReadToEnd waiting for ever.
    $text = ""
    if ($outTask.Wait(5000)) { $text = $outTask.Result }
    [void]$errTask.Wait(1000)
    return @{ Out = ($text -replace "`r", ""); Code = $p.ExitCode }
}

function Invoke-Dev([string[]]$Arguments, [int]$TimeoutSec = 60) {
    return Invoke-Adb (@("-s", $script:Serial) + $Arguments) $TimeoutSec
}

function Get-File([string]$Url, [string]$Dest) {
    $old = $ProgressPreference
    $ProgressPreference = "SilentlyContinue"   # thanh tien trinh cua PS 5.1 lam tai cham gap nhieu lan
    try { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Dest }
    finally { $ProgressPreference = $old }
}

# -- 1. adb ---------------------------------------------------------------
# Khoi dong adb server truoc, va KHONG chuyen huong dau ra cua lenh nay: tren Windows tien trinh
# server thua ke moi ong dan cua lenh sinh ra no va giu chung mai mai, nen lenh dau tien co chuyen
# huong se treo. Sau buoc nay server da chay san, cac lenh co chuyen huong ben duoi khong sinh ra no nua.
function Start-AdbServer {
    & $script:Adb start-server
}

function Find-Adb([string]$Base) {
    Step "Kiem tra cong cu adb"
    $exe = "adb.exe"
    if ($env:OS -ne "Windows_NT") { $exe = "adb" }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    $local = Join-Path (Join-Path $Base "platform-tools") $exe
    if ($cmd) { $script:Adb = $cmd.Source; Say "Da co adb."; Start-AdbServer; return }
    if (Test-Path $local) { $script:Adb = $local; Say "Dung adb trong thu muc bo cai."; Start-AdbServer; return }
    Say "Chua co adb - dang tai Android platform-tools tu Google (khoang 7 MB)..."
    $zip = Join-Path $Base "platform-tools.zip"
    try { Get-File $ToolsUrl $zip } catch { throw "Tai platform-tools that bai: $($_.Exception.Message)" }
    Expand-Archive -Path $zip -DestinationPath $Base -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $local)) { throw "Khong tim thay adb sau khi giai nen." }
    $script:Adb = $local
    Start-AdbServer
}

# -- 2. Tep cai dat -------------------------------------------------------
function Find-Apk([string]$Base) {
    Step "Chuan bi tep cai dat DB-Robot"
    $have = Get-ChildItem -Path $Base -Filter "DB-Robot-R1*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($have) { Say "Dung tep co san: $($have.FullName)"; return $have.FullName }
    $apk = Join-Path $Base "DB-Robot-R1.apk"
    Say "Dang tai ban moi nhat..."
    try { Get-File $ApkUrl $apk } catch { throw "Tai tep cai dat that bai. Kiem tra Internet roi chay lai. ($($_.Exception.Message))" }
    # Tep APK la tep zip: bat dau bang "PK" va nang nhieu MB. Trang bao loi thi khong.
    $ok = $false
    if ((Test-Path $apk) -and ((Get-Item $apk).Length -gt 1000000)) {
        $fs = [System.IO.File]::OpenRead($apk)
        try { $ok = ($fs.ReadByte() -eq 0x50) -and ($fs.ReadByte() -eq 0x4B) } finally { $fs.Close() }
    }
    if (-not $ok) { Remove-Item $apk -Force -ErrorAction SilentlyContinue; throw "Tep tai ve khong phai tep cai dat hop le." }
    Say "Da tai xong."
    return $apk
}

# -- 3. Tim loa -----------------------------------------------------------
# Ba so dau cua dia chi IP may nay (vi du 192.168.1), lay tu card mang dang co cong ra Internet.
function Get-LocalPrefixes {
    $list = @()
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($nic.OperationalStatus -ne "Up") { continue }
        if ($nic.NetworkInterfaceType -eq "Loopback") { continue }
        $props = $nic.GetIPProperties()
        if ($props.GatewayAddresses.Count -eq 0) { continue }
        foreach ($u in $props.UnicastAddresses) {
            if ($u.Address.AddressFamily -ne "InterNetwork") { continue }
            $ip = $u.Address.ToString()
            if ($ip.StartsWith("169.254.")) { continue }
            $prefix = $ip.Substring(0, $ip.LastIndexOf("."))
            if ($list -notcontains $prefix) { $list += $prefix }
        }
    }
    return $list
}

# Thu noi toi cong adb cua ca 254 dia chi cung luc; tra ve nhung dia chi co mo cong.
function Find-OpenAdb([string]$Prefix) {
    $tries = @()
    foreach ($i in 1..254) {
        $ip = "$Prefix.$i"
        $c = New-Object System.Net.Sockets.TcpClient
        try { $ar = $c.BeginConnect($ip, $Port, $null, $null); $tries += ,@($ip, $c, $ar) } catch { $c.Close() }
    }
    Start-Sleep -Milliseconds 1500
    $found = @()
    foreach ($t in $tries) {
        try { if ($t[2].IsCompleted -and $t[1].Connected) { $found += $t[0] } } catch { }
        try { $t[1].Close() } catch { }
    }
    return $found
}

function Select-Speaker([string]$Given) {
    Step "Tim loa trong mang"
    $ip = $Given
    if (-not $ip -and $env:LOA_IP) { $ip = $env:LOA_IP }
    if (-not $ip) { $ip = (Read-Host "Nhap dia chi IP cua loa (bam Enter de tu tim trong mang Wi-Fi)").Trim() }
    if (-not $ip) {
        $prefixes = @()
        if ($env:LOA_PREFIX) { $prefixes = @($env:LOA_PREFIX) } else { $prefixes = @(Get-LocalPrefixes) }
        if ($prefixes.Count -eq 0) { throw "Khong xac dinh duoc mang Wi-Fi cua may nay. Hay xem IP cua loa trong trang quan ly modem Wi-Fi roi nhap truc tiep." }
        $found = @()
        foreach ($p in $prefixes) {
            Say "Dang quet mang $p.x ..."
            $found += @(Find-OpenAdb $p)
        }
        if ($found.Count -eq 0) { throw "Khong thay thiet bi nao mo cong adb ($Port). Kiem tra loa da bat va cung mang Wi-Fi voi may nay, hoac nhap IP truc tiep." }
        $n = 0
        foreach ($f in $found) {
            $n++
            [void](Invoke-Adb @("connect", "${f}:$Port") 15)
            $model = (Invoke-Adb @("-s", "${f}:$Port", "shell", "getprop ro.product.model") 15).Out.Trim()
            # Chi hoi ten; khong giu ket noi voi may khong duoc chon.
            [void](Invoke-Adb @("disconnect", "${f}:$Port") 15)
            if (-not $model) { $model = "(chua ro thiet bi)" }
            Say "  $n) $f   $model"
        }
        if ($found.Count -eq 1) {
            $ip = $found[0]
            if (-not (Ask-YesNo "Cai DB-Robot vao thiet bi ${ip} ? [C/k]" "c")) { throw "Da huy." }
        } else {
            $pick = 0
            [void][int]::TryParse((Read-Host "Chon so thu tu cua loa (1-$($found.Count))"), [ref]$pick)
            if ($pick -lt 1 -or $pick -gt $found.Count) { throw "Lua chon khong hop le." }
            $ip = $found[$pick - 1]
        }
    }
    if ($ip -match ":") { $script:Serial = $ip } else { $script:Serial = "${ip}:$Port" }
}

# Ngat ket noi adb toi loa. Bat buoc phai lam khi xong: loa chi phuc vu duoc mot may qua adb tai
# mot thoi diem, nen neu may nay cu giu ket noi thi loa khong goi duoc trinh cai dat cua chinh no
# - nut "Cap nhat ngay" tren trang dieu khien se bao loi cho toi khi may nay tat.
function Disconnect-Speaker {
    if ($script:Serial) { [void](Invoke-Adb @("disconnect", $script:Serial) 15) }
}

# -- 4. Noi chuyen voi loa ------------------------------------------------
function Connect-Speaker {   # thu vai lan; adb qua Wi-Fi cua loa nay hay rot
    foreach ($try in 1..5) {
        [void](Invoke-Adb @("connect", $script:Serial) 15)
        if ((Invoke-Dev @("get-state") 15).Out.Trim() -eq "device") { return $true }
        [void](Invoke-Adb @("disconnect", $script:Serial) 15)
        Start-Sleep -Seconds 2
    }
    return $false
}

# Chay mot lenh tren loa va cho no xong; tra ve noi dung lenh in ra, hoac $null neu qua thoi gian.
# Khong goi thang "adb shell <lenh>": cac lenh chay lau (pm install mat 1-2 phut) lam ket noi adb
# cua loa rot giua chung ("error: closed") va lenh chet theo. Thay vao do lenh chay nen tren loa,
# ghi ket qua ra tep, con o day chi doc tep cho toi khi thay dong ket thuc.
# Moi lan goi co mot ma rieng ghi o dong dau va dong cuoi cua tep, nho vay phan biet duoc ba
# truong hop: lenh chua he chay (gui lai), dang chay (cho tiep), va tep cu cua lan goi truoc.
function Invoke-OnSpeaker([string]$Command, [int]$LimitSec = 60) {
    $token = "t" + (Get-Random -Minimum 100000 -Maximum 999999)
    $launch = 'trap '''' HUP; ( echo begin ' + $token + '; ' + $Command + '; echo exit=$? end ' + $token + ' ) > ' + $DevLog + ' 2>&1 &'
    $read = 'cat ' + $DevLog + ' 2>/dev/null || echo NOLOG'
    $waited = 0; $launched = 0; $relaunch = $true
    while ($waited -lt $LimitSec) {
        if ($relaunch -and $launched -lt 3) {
            [void](Connect-Speaker)
            [void](Invoke-Dev @("shell", $launch) 20)
            $launched++; $relaunch = $false
        }
        Start-Sleep -Seconds 3; $waited += 3
        $out = (Invoke-Dev @("shell", $read) 20).Out
        if ($out -match "end $token") { Write-Host ""; return $out }
        elseif ($out -match "begin $token") { }                       # dang chay tren loa: cho tiep
        elseif ($out -match "^NOLOG" -or $out -match "begin t") { $relaunch = $true }   # lenh chua chay
        else { [void](Connect-Speaker) }                              # ket noi rot: noi lai roi doc tiep
        Write-Host "." -NoNewline
    }
    Write-Host ""
    return $null
}

function Install-Apk([string]$Apk) {
    Step "Chep tep cai dat sang loa"
    $pushed = $false
    foreach ($try in 1..3) {
        if (-not (Connect-Speaker)) { throw "Khong ket noi duoc toi $($script:Serial). Kiem tra IP, loa da bat va cung mang Wi-Fi. Neu van khong duoc, rut dien loa 10 giay roi cam lai." }
        $r = Invoke-Dev @("push", $Apk, $DevApk) 300
        if ($r.Code -eq 0) { $pushed = $true; Say "Da chep xong."; break }
        Say "Chep bi gian doan, thu lai ($try/3)..."
        Start-Sleep -Seconds 2
    }
    if (-not $pushed) { throw "Khong chep duoc tep sang loa." }

    Step "Cai dat (loa cu nen mat 1-3 phut, dung tat cua so nay)"
    $out = Invoke-OnSpeaker "pm install -r $DevApk" 420
    if ($null -eq $out) { throw "Cho qua lau ma loa chua cai xong. Rut dien loa 10 giay, cam lai roi chay lai bo cai." }
    if ($out -match "Success") { Say "Cai dat thanh cong."; return }
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        Say "Tren loa dang co mot ban DB-Robot cu ky bang khoa khac nen khong cai de duoc."
        Say "Can go ban cu truoc - cac cai dat cua ban cu se mat va loa phai kich hoat lai voi may chu."
        if (-not (Ask-YesNo "Go ban cu roi cai lai? [c/K]" "k")) { throw "Da dung theo yeu cau. Loa van giu nguyen ban cu." }
        if ($null -eq (Invoke-OnSpeaker "pm uninstall $Pkg" 120)) { throw "Khong go duoc ban cu." }
        $out = Invoke-OnSpeaker "pm install -r $DevApk" 420
        if ($null -eq $out) { throw "Cho qua lau ma loa chua cai xong." }
        if ($out -match "Success") { Say "Cai dat thanh cong."; return }
    }
    if ($out -match "INSTALL_FAILED_VERSION_DOWNGRADE") { throw "Loa dang chay ban DB-Robot moi hon tep nay - khong can cai." }
    if ($out -match "INSTALL_FAILED_INSUFFICIENT_STORAGE") { throw "Bo nho cua loa da day. Hay go bot ung dung tren loa roi chay lai." }
    $why = ($out -split "`n" | Where-Object { $_ -match "Failure" } | Select-Object -First 1)
    if (-not $why) { $why = ($out -split "`n" | Where-Object { $_ -and $_ -notmatch "^(begin|exit=)" } | Select-Object -Last 1) }
    throw "Cai dat that bai: $why"
}

# AI Box Plus (va ban xiaozhi goc) giu micro lien tuc; de nguyen thi DB-Robot khong nghe duoc gi.
function Resolve-Rivals {
    $list = Invoke-OnSpeaker "pm list packages" 45
    if ($null -eq $list) { return }
    $lines = $list -split "`n" | ForEach-Object { $_.Trim() }
    $found = @($Rivals | Where-Object { $lines -contains "package:$_" })
    if ($found.Count -eq 0) { return }
    Step "Ung dung tro ly khac tren loa"
    Say "Loa dang co ung dung tro ly khac: $($found -join ', ')"
    Say "No giu micro nen DB-Robot se khong nghe duoc neu ca hai cung chay."
    Say "Tat no khong xoa gi ca; muon dung lai chi can chay: adb shell pm enable <ten-goi>"
    if (-not (Ask-YesNo "Tat ung dung do de DB-Robot dung micro? [C/k]" "c")) {
        Say "Giu nguyen. Luu y DB-Robot co the khong nghe duoc lenh."
        return
    }
    foreach ($p in $found) {
        $out = Invoke-OnSpeaker "pm disable-user $p" 45
        if ($out -match "disabled") { Say "Da tat $p." }
        else {
            [void](Invoke-Dev @("shell", "am force-stop $p") 20)
            Say "Khong tat han duoc $p (da tam dung; no se chay lai khi loa khoi dong lai)."
        }
    }
}

function Start-App {
    Step "Khoi dong DB-Robot"
    foreach ($try in 1..3) {
        [void](Connect-Speaker)
        $r = Invoke-Dev @("shell", "am force-stop $Pkg; am start -n $Activity") 30
        if ($r.Out -match "Starting") { Say "DB-Robot dang chay."; return }
        Start-Sleep -Seconds 2
    }
    Say "Chua khoi dong duoc ung dung - hay rut dien loa roi cam lai, DB-Robot se tu chay."
}

function Install-DBRobot([string]$GivenIp) {
    Say "=============================================="
    Say "  DB-Robot R1 - cai dat vao loa Phicomm R1"
    Say "=============================================="
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
    $base = $PSScriptRoot
    if (-not $base) { $base = Join-Path ([System.IO.Path]::GetTempPath()) "db-robot-r1" }
    if (-not (Test-Path $base)) { [void](New-Item -ItemType Directory -Path $base -Force) }

    Find-Adb $base
    $apk = Find-Apk $base
    Select-Speaker $GivenIp
    Install-Apk $apk
    Resolve-Rivals
    Start-App
    [void](Invoke-Dev @("shell", "rm -f $DevApk $DevLog") 20)
    Disconnect-Speaker

    $hostIp = $script:Serial.Substring(0, $script:Serial.LastIndexOf(":"))
    Say ""
    Say "=============================================="
    Say "  XONG. Mo trang dieu khien cua loa:"
    Say "      http://${hostIp}:8088"
    Say "  Tab Setup -> bam may chu DB-Robot de ket noi."
    Say "  Cac ban moi ve sau: cap nhat ngay trong tab Setup."
    Say "=============================================="
}

# Khong dung "exit": khi chay bang "irm ... | iex" lenh do dong luon cua so PowerShell cua nguoi dung.
try { Install-DBRobot $Ip }
catch { Disconnect-Speaker; Write-Host ""; Write-Host "[LOI] $($_.Exception.Message)" -ForegroundColor Red }
