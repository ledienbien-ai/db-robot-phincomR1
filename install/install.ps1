# DB-Robot R1 - bo cai cho Windows.
#
#   Cach dung:  bam dup DB-Robot-R1-Setup.exe (tep nay nam san ben trong), hoac
#               bam dup vao "Cai-dat-DB-Robot.bat" trong ban zip (cung thu muc voi tep nay)
#   Hoac dan dong sau vao PowerShell:
#     irm https://github.com/ledienbien-ai/db-robot-phincomR1/releases/latest/download/install.ps1 | iex
#
# Script tai ban DB-Robot moi nhat, ket noi toi loa Phicomm R1 qua adb tren Wi-Fi va cai vao loa.
# Chi can lam mot lan: cac ban sau cap nhat ngay trong trang dieu khien cua loa (http://IP-loa:8088).
#
# Loa moi, chua vao Wi-Fi nao: bo cai huong dan noi may nay vao mang do chinh loa phat, cai qua
# dia chi 192.168.43.1, roi hoi ten va mat khau Wi-Fi nha de chuyen loa sang.
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
$PanelPort = 8088
# Dia chi cua loa khi chinh no phat Wi-Fi (che do cai dat mang: giu nut tren dinh loa 5 giay,
# loa phat mang "Phicomm R1"). Android 5.1 luon dung dia chi nay.
$HotspotIp = "192.168.43.1"
if ($env:LOA_AP_IP) { $HotspotIp = $env:LOA_AP_IP }
$DevApk   = "/data/local/tmp/dbrobot.apk"
$DevLog   = "/data/local/tmp/dbrobot-cmd.log"
# Ung dung tro ly khac dung chung micro voi DB-Robot (AI Box Plus va ban xiaozhi goc).
$Rivals   = @("info.dourok.voicebot", "info.dourok.voicebot.dev")

$script:Adb      = "adb"
$script:Serial   = ""
$script:HomeSsid = ""     # Wi-Fi may nay dang dung luc bat dau: goi y cho buoc chuyen loa sang mang nha

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

# May dang noi vao mang cua loa thi khong co Internet: noi ro thay vi chi bao "tai that bai".
function Get-OfflineHint {
    $prefix = $HotspotIp.Substring(0, $HotspotIp.LastIndexOf("."))
    if (@(Get-LocalPrefixes) -contains $prefix) {
        return " May nay dang noi vao mang cua loa nen khong co Internet: hay noi lai Wi-Fi nha roi chay lai - bo cai se bao khi nao can chuyen sang mang cua loa."
    }
    return ""
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
    try { Get-File $ToolsUrl $zip } catch { throw "Tai platform-tools that bai: $($_.Exception.Message)$(Get-OfflineHint)" }
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
    try { Get-File $ApkUrl $apk } catch { throw "Tai tep cai dat that bai. Kiem tra Internet roi chay lai. ($($_.Exception.Message))$(Get-OfflineHint)" }
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

# Thu noi toi mot cong cua ca 254 dia chi cung luc; tra ve nhung dia chi co mo cong do.
function Find-OpenPort([string]$Prefix, [int]$PortNum) {
    $tries = @()
    foreach ($i in 1..254) {
        $ip = "$Prefix.$i"
        $c = New-Object System.Net.Sockets.TcpClient
        try { $ar = $c.BeginConnect($ip, $PortNum, $null, $null); $tries += ,@($ip, $c, $ar) } catch { $c.Close() }
    }
    Start-Sleep -Milliseconds 1500
    $found = @()
    foreach ($t in $tries) {
        try { if ($t[2].IsCompleted -and $t[1].Connected) { $found += $t[0] } } catch { }
        try { $t[1].Close() } catch { }
    }
    return $found
}

function Test-Port([string]$Ip, [int]$PortNum, [int]$TimeoutMs = 1500) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $c.BeginConnect($Ip, $PortNum, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        return $c.Connected
    } catch { return $false }
    finally { try { $c.Close() } catch { } }
}

# Ten mang Wi-Fi may nay dang noi (chi Windows). Rong neu khong ro, hoac ten co ky tu ma cua so
# lenh khong the hien dung - thay vi goi y mot cai ten sai.
function Get-CurrentSsid {
    if ($env:OS -ne "Windows_NT") { return "" }
    try {
        foreach ($l in @(& netsh wlan show interfaces 2>$null)) {
            if ($l -match '^\s*SSID\s*:\s*(.+?)\s*$') {
                $name = $Matches[1]
                if ($name -match '^[\x20-\x7E]+$') { return $name }
                return ""
            }
        }
    } catch { }
    return ""
}

# Mang do loa phat ra, khong phai Wi-Fi nha.
function Test-SpeakerSsid([string]$Name) { return ($Name -match '^Phicomm[ _-]?R1') }

# Loa moi chua vao Wi-Fi nao: dua may nay sang mang do chinh loa phat, roi cho toi khi thay loa.
function Join-SpeakerHotspot {
    Step "Loa moi, chua vao Wi-Fi: cai qua mang do chinh loa phat"
    Say "  1. Cam dien loa va cho loa khoi dong xong (khoang 1 phut)."
    Say "  2. Giu nut tren dinh loa (nut nguon) khoang 5 giay: loa phat mot mang Wi-Fi"
    Say "     ten ""Phicomm R1""."
    Say "  3. Tren may nay, mo danh sach Wi-Fi va noi vao mang ""Phicomm R1""."
    Say "     May bao ""khong co Internet"" la binh thuong - cu giu ket noi do."
    while ($true) {
        $r = (Read-Host "Noi xong thi bam Enter (go K de thoat)").Trim()
        if ($r -match '^[kK]') { throw "Da huy." }
        Say "Dang tim loa o dia chi $HotspotIp ..."
        foreach ($try in 1..6) {
            if (Test-Port $HotspotIp $Port 2000) { Say "Da thay loa."; return }
            Start-Sleep -Seconds 2
        }
        Say "Chua thay loa. Kiem tra may nay da noi dung vao mang ""Phicomm R1"" cua loa chua,"
        Say "va tat VPN neu dang bat."
    }
}

function Select-Speaker([string]$Given) {
    Step "Tim loa trong mang"
    $ip = $Given
    if (-not $ip -and $env:LOA_IP) { $ip = $env:LOA_IP }
    if (-not $ip) {
        Say "Loa da vao Wi-Fi nha: bam Enter de tu tim, hoac go dia chi IP cua loa."
        Say "Loa moi, chua vao Wi-Fi nao: go M."
        $ip = (Read-Host "IP cua loa / Enter / M").Trim()
    }
    if ($ip -match '^[mM]$') { Join-SpeakerHotspot; $ip = $HotspotIp }
    if (-not $ip) {
        $prefixes = @()
        if ($env:LOA_PREFIX) { $prefixes = @($env:LOA_PREFIX -split ",") } else { $prefixes = @(Get-LocalPrefixes) }
        $found = @()
        foreach ($p in $prefixes) {
            Say "Dang quet mang $p.x ..."
            $found += @(Find-OpenPort $p $Port)
        }
        if ($found.Count -eq 0) {
            if ($prefixes.Count -eq 0) { Say "Khong xac dinh duoc mang cua may nay." }
            else { Say "Khong thay thiet bi nao mo cong adb ($Port) trong mang nay." }
            Say "Neu loa da vao Wi-Fi nha: kiem tra loa da bat va cung mang voi may nay, hoac xem IP"
            Say "cua loa trong trang quan ly modem Wi-Fi roi chay lai va nhap truc tiep."
            if (-not (Ask-YesNo "Hay day la loa moi, chua vao Wi-Fi nao? [C/k]" "c")) { throw "Khong tim thay loa." }
            Join-SpeakerHotspot
            $found = @($HotspotIp)
        }
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
            if ($ip -ne $HotspotIp -and -not (Ask-YesNo "Cai DB-Robot vao thiet bi ${ip} ? [C/k]" "c")) { throw "Da huy." }
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
    # Phan mem goc cua Phicomm: chi ke ten, khong dong toi. Danh sach nay giup tim nguyen nhan neu
    # tren loa nguyen ban DB-Robot khong nghe duoc lenh.
    $stock = @($lines | Where-Object { $_ -like "package:com.phicomm*" } | ForEach-Object { $_.Substring(8) })
    if ($stock.Count -gt 0) {
        Say "Phan mem goc Phicomm tren loa (bo cai de nguyen): $($stock -join ', ')"
        Say "Neu DB-Robot khong nghe duoc lenh, hay gui dong tren cho nhom ho tro (dbrobot.vn)."
    }
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

# -- 5. Loa moi: chuyen loa tu mang rieng sang Wi-Fi nha -------------------
# Goi trang dieu khien cua loa. Tra ve noi dung tra loi, hoac $null neu khong goi duoc.
# Dung HttpWebRequest thay cho Invoke-WebRequest: khong di qua proxy cua may (day la dia chi
# trong nha) va gui duoc ten mang co dau duoi dang UTF-8.
function Invoke-Panel([string]$Url, [string]$Body = "", [switch]$Post, [int]$TimeoutSec = 6) {
    try {
        $req = [System.Net.WebRequest]::Create($Url)
        $req.Timeout = $TimeoutSec * 1000
        $req.ReadWriteTimeout = $TimeoutSec * 1000
        $req.Proxy = $null
        $req.KeepAlive = $false
        if ($Post) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $req.Method = "POST"
            # Phai ghi ro charset: thieu no may chu tren loa doc noi dung theo ASCII, mat dau.
            $req.ContentType = "application/json; charset=utf-8"
            $req.ContentLength = $bytes.Length
            try { $req.ServicePoint.Expect100Continue = $false } catch { }
            $out = $req.GetRequestStream()
            try { $out.Write($bytes, 0, $bytes.Length) } finally { $out.Close() }
        }
        $resp = $req.GetResponse()
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            return $reader.ReadToEnd()
        } finally { $resp.Close() }
    } catch { return $null }
}

function Read-PanelJson([string]$Url, [int]$TimeoutSec = 6) {
    $text = Invoke-Panel $Url -TimeoutSec $TimeoutSec
    if (-not $text) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

# Cac mang loa nghe thay, manh nhat truoc. Moi dong loa tra ve: "bao-mat TAB so-vach TAB ten".
# Khi loa dang phat Wi-Fi bang phan mem goc thi danh sach rong (loa khong vua phat vua quet duoc).
function Get-SpeakerNetworks([string]$Base) {
    $rows = @()
    $text = Invoke-Panel "$Base/api/wifi/list"
    if (-not $text) { return $rows }
    foreach ($line in ($text -split "`n")) {
        $f = $line.TrimEnd("`r") -split "`t", 3
        if ($f.Count -lt 3 -or -not $f[2]) { continue }
        if (Test-SpeakerSsid $f[2]) { continue }           # mang cai dat cua mot loa R1 khac
        $rows += ,@{ Security = $f[0]; Bars = $f[1]; Ssid = $f[2] }
    }
    return $rows
}

# Hoi ten va mat khau Wi-Fi nha. Tra ve @{ Ssid; Password; Security }, hoac $null neu nguoi dung bo qua.
function Read-HomeWifi($Networks) {
    $shown = @($Networks | Select-Object -First 12)
    if ($shown.Count -gt 0) {
        Say "Cac mang Wi-Fi loa nghe thay:"
        $n = 0
        foreach ($w in $shown) {
            $n++
            $lock = "co mat khau"
            if ($w.Security -eq "open") { $lock = "khong mat khau" }
            Say ("  {0,2}) {1}   [song {2}/4, {3}]" -f $n, $w.Ssid, $w.Bars, $lock)
        }
    }
    $hint = "Ten Wi-Fi nha ban"
    if ($shown.Count -gt 0) { $hint = "Chon so thu tu, hoac go ten Wi-Fi nha ban" }
    if ($script:HomeSsid) { $hint += " (Enter = ""$($script:HomeSsid)"")" }
    $hint += "; go K de bo qua"
    $ssid = ""; $security = ""
    while (-not $ssid) {
        $r = (Read-Host $hint).Trim()
        if ($r -match '^[kK]$') { return $null }
        if (-not $r) { $r = $script:HomeSsid }
        if (-not $r) { continue }
        $pick = 0
        if ([int]::TryParse($r, [ref]$pick) -and $pick -ge 1 -and $pick -le $shown.Count) {
            $ssid = $shown[$pick - 1].Ssid; $security = $shown[$pick - 1].Security
        } else {
            $ssid = $r
            foreach ($w in $Networks) { if ($w.Ssid -ceq $ssid) { $security = $w.Security } }
        }
    }
    $password = ""
    if ($security -ne "open") {
        $password = Read-Host "Mat khau cua ""$ssid"" (de trong neu mang khong dat mat khau)"
        if (-not $security) { if ($password) { $security = "psk" } else { $security = "open" } }
    }
    return @{ Ssid = $ssid; Password = $password; Security = $security }
}

# Sau khi loa roi mang rieng: tim no trong mang nha bang ma thiet bi doc duoc luc truoc.
# Tra ve @{ Ip } khi thay, @{ Failed; Message } khi loa da quay lai phat Wi-Fi, $null khi het gio.
function Wait-SpeakerAtHome([string]$DeviceId, [string]$Ssid, [int]$LimitSec) {
    $apPrefix = $HotspotIp.Substring(0, $HotspotIp.LastIndexOf("."))
    $waited = 0
    while ($waited -lt $LimitSec) {
        Start-Sleep -Seconds 4; $waited += 4
        Write-Host "." -NoNewline
        $prefixes = @(Get-LocalPrefixes)
        if ($env:LOA_PREFIX) { $prefixes += @($env:LOA_PREFIX -split ",") }
        foreach ($p in $prefixes) {
            if ($p -eq $apPrefix) {
                # May nay van (hoac lai) o mang cua loa: loa da thu xong va khong vao duoc?
                $s = Read-PanelJson "http://${HotspotIp}:$PanelPort/api/wifi/state" 3
                if ($s -and $s.ap -and $s.job -and $s.job.state -eq "failed" -and $s.job.ssid -ceq $Ssid) {
                    Write-Host ""
                    return @{ Failed = $true; Message = [string]$s.job.message }
                }
                continue
            }
            $waited += 2
            foreach ($ip in @(Find-OpenPort $p $PanelPort)) {
                $st = Read-PanelJson "http://${ip}:$PanelPort/api/state" 4
                if (-not $st -or -not $st.device_id) { continue }
                if ($DeviceId -and $st.device_id -ne $DeviceId) { continue }
                Write-Host ""
                return @{ Ip = $ip }
            }
        }
    }
    Write-Host ""
    return $null
}

# Tra ve dia chi moi cua loa trong mang nha, hoac $null neu chua chuyen duoc.
function Move-SpeakerToHome {
    Step "Dua loa vao Wi-Fi nha ban"
    $base = "http://${HotspotIp}:$PanelPort"
    Say "Dang cho DB-Robot tren loa san sang..."
    $state = $null
    foreach ($try in 1..30) {
        $state = Read-PanelJson "$base/api/wifi/state"
        if ($state -and $state.ok) { break }
        $state = $null
        Start-Sleep -Seconds 3
    }
    if (-not $state) {
        Say "Chua goi duoc trang dieu khien cua loa. Hay mo $base bang trinh duyet tren may nay,"
        Say "vao tab System -> Wi-Fi de chon mang nha."
        return $null
    }
    if (-not $state.ap) { return $HotspotIp }     # loa khong phat Wi-Fi: no da o trong mot mang roi
    $id = ""
    $st = Read-PanelJson "$base/api/state"
    if ($st -and $st.device_id) { $id = [string]$st.device_id }

    while ($true) {
        $wifi = Read-HomeWifi @(Get-SpeakerNetworks $base)
        if ($null -eq $wifi) { return $null }
        $body = (@{ ssid = $wifi.Ssid; password = $wifi.Password; security = $wifi.Security } | ConvertTo-Json -Compress)
        $reply = $null
        $text = Invoke-Panel "$base/api/wifi/connect" -Body $body -Post -TimeoutSec 10
        if ($text) { try { $reply = $text | ConvertFrom-Json } catch { } }
        if ($reply -and -not $reply.ok) {
            Say "Loa tu choi: $($reply.error)"
            continue
        }
        if (-not $reply) {
            Say "Loa khong tra loi. Kiem tra may nay con noi vao mang cua loa khong, roi thu lai."
            continue
        }
        Say "Loa dang roi che do cai dat de vao ""$($wifi.Ssid)"" (mat khoang nua phut)."
        Say "Bay gio hay noi may nay tro lai Wi-Fi ""$($wifi.Ssid)"" - Windows thuong tu noi lai."
        $limit = 90
        while ($true) {
            $r = Wait-SpeakerAtHome $id $wifi.Ssid $limit
            if ($r -and $r.Ip) { Say "Da thay loa trong mang nha: $($r.Ip)"; return $r.Ip }
            if ($r -and $r.Failed) {
                Say "Loa khong vao duoc mang ""$($wifi.Ssid)"": $($r.Message)"
                break
            }
            Say "Chua thay loa trong mang cua may nay."
            Say " - May nay da noi lai Wi-Fi nha chua? Noi xong bam Enter de tim tiep."
            Say " - Neu mang ""Phicomm R1"" cua loa hien lai trong danh sach Wi-Fi: loa chua vao duoc"
            Say "   mang nha (thuong do sai mat khau). Noi may nay vao mang do roi go M de nhap lai."
            $a = (Read-Host "Enter = tim tiep, M = nhap lai Wi-Fi, K = ket thuc").Trim()
            if ($a -match '^[kK]') { return $null }
            if ($a -match '^[mM]') {
                if (Test-Port $HotspotIp $PanelPort 3000) { break }
                Say "May nay chua noi vao mang cua loa (khong goi duoc $HotspotIp)."
            }
            $limit = 40
        }
    }
}

function Install-DBRobot([string]$GivenIp) {
    Say "=============================================="
    Say "  DB-Robot R1 - cai dat vao loa Phicomm R1"
    Say "=============================================="
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
    $base = $PSScriptRoot
    if (-not $base) { $base = Join-Path ([System.IO.Path]::GetTempPath()) "db-robot-r1" }
    if (-not (Test-Path $base)) { [void](New-Item -ItemType Directory -Path $base -Force) }
    $ssid = Get-CurrentSsid
    if ($ssid -and -not (Test-SpeakerSsid $ssid)) { $script:HomeSsid = $ssid }

    # adb va tep cai dat truoc, tim loa sau: voi loa moi, den buoc tim loa may nay moi phai roi
    # Wi-Fi nha (va mat Internet) de sang mang cua loa.
    Find-Adb $base
    $apk = Find-Apk $base
    Select-Speaker $GivenIp
    Install-Apk $apk
    Resolve-Rivals
    Start-App
    [void](Invoke-Dev @("shell", "rm -f $DevApk $DevLog") 20)
    Disconnect-Speaker

    $hostIp = $script:Serial.Substring(0, $script:Serial.LastIndexOf(":"))
    if ($hostIp -eq $HotspotIp) {
        $hostIp = Move-SpeakerToHome
        if (-not $hostIp) {
            Say ""
            Say "=============================================="
            Say "  DA CAI XONG DB-Robot, nhung chua thay loa trong Wi-Fi nha."
            Say "  - Loa da vao mang: xem IP cua loa trong trang quan ly modem,"
            Say "    roi mo http://IP-cua-loa:$PanelPort"
            Say "  - Loa chua vao mang: noi dien thoai hoac may tinh vao mang"
            Say "    ""Phicomm R1"" cua loa (chua thay mang do thi giu nut tren dinh"
            Say "    loa 5 giay), mo http://${HotspotIp}:$PanelPort -> tab System -> Wi-Fi."
            Say "=============================================="
            return
        }
    }
    Say ""
    Say "=============================================="
    Say "  XONG. Mo trang dieu khien cua loa:"
    Say "      http://${hostIp}:$PanelPort"
    Say "  Tab System -> bam may chu DB-Robot de ket noi."
    Say "  Cac ban moi ve sau: cap nhat ngay trong tab System."
    Say "=============================================="
}

# Khong dung "exit": khi chay bang "irm ... | iex" lenh do dong luon cua so PowerShell cua nguoi dung.
try { Install-DBRobot $Ip }
catch { Disconnect-Speaker; Write-Host ""; Write-Host "[LOI] $($_.Exception.Message)" -ForegroundColor Red }
