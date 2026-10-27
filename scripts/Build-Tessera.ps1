# 编译 Tessera 桌面 APK（不用 Gradle：javac → d8 → aapt2 → zipalign → apksigner）。
#
#   .\scripts\Build-Tessera.ps1 -Sdk D:\android-sdk -KeyStore D:\keys\my.jks
#
# 参数都有默认值，也可以用环境变量：
#   -Sdk       Android SDK 目录，要有 build-tools\android-15 和 platforms\android-11（默认 $env:ANDROID_HOME）
#   -Design    Tessera 设计稿目录（components\bundle.js 等，WebView 版页面用）；没有就跳过，原生界面不受影响
#   -Out       输出目录（默认仓库里的 build\，已在 .gitignore）
#   -Jdk       JDK 目录（要有 javac 和 jar；默认 $env:JAVA_HOME，没有就到 Program Files\Java 下找）
#   -KeyStore  签名密钥（默认 $env:TESSERA_KEYSTORE）；密码取 $env:TESSERA_KEYSTORE_PASS
#              不给密钥就只出未签名的 aligned.apk，自己签名后再装
param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Design = "$env:USERPROFILE\Desktop\Tessera",
    [string]$Out = (Join-Path $PSScriptRoot '..\build'),
    [string]$KeyStore = $env:TESSERA_KEYSTORE,
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$proj = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw "找不到 Android SDK：用 -Sdk 指定，或设置 ANDROID_HOME" }
$bt      = "$Sdk\build-tools\android-15"
$android = "$Sdk\platforms\android-11\android.jar"
$work    = "$Out\tessera"
if ($Jdk -and (Test-Path "$Jdk\bin\jar.exe")) { $env:PATH = "$Jdk\bin;$env:PATH" }
elseif (-not (Get-Command jar -ErrorAction SilentlyContinue)) {
    $found = Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path "$($_.FullName)\bin\jar.exe" } | Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $env:PATH = "$($found.FullName)\bin;$env:PATH" } else { throw "找不到 JDK（要有 javac 和 jar）：用 -Jdk 指定，或设置 JAVA_HOME" }
}

function Invoke-Tool([string]$exe) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $o = & $exe @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $saved }
    if ($LASTEXITCODE -ne 0) { $o | Select-Object -Last 30 | Write-Host; throw "$exe 失败 ($LASTEXITCODE)" }
}

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
$assets = "$work\assets"
New-Item -ItemType Directory -Force "$assets\tessera\components", "$assets\tessera\preview\lib", "$assets\tessera\fonts", "$work\classes", "$work\dex" | Out-Null

# 1. 设计稿原样拷进来（WebView 版页面用；原生界面不依赖它）
if (Test-Path "$Design\components\bundle.js") {
    Copy-Item "$Design\components\bundle.js", "$Design\components\bundle.css" "$assets\tessera\components\"
    Copy-Item "$Design\preview\tokens.css" "$assets\tessera\preview\"
    Copy-Item "$Design\preview\lib\*.js" "$assets\tessera\preview\lib\"
    Copy-Item "$Design\fonts\*" "$assets\tessera\fonts\"
} else {
    Write-Warning "没有设计稿目录 $Design ，跳过（只影响 WebView 版页面）"
}
# 2. 外壳页面（字体目录只要 css 和 woff2）
Copy-Item "$proj\web\*" $assets -Recurse
Remove-Item "$assets\noto\google.css", "$assets\noto\urls.txt" -ErrorAction SilentlyContinue

# 3. 原生代码
$sources = Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object FullName
Invoke-Tool javac -encoding UTF-8 --release 11 -nowarn -classpath $android -d "$work\classes" @sources
# 类文件太多时逐个传给 d8.bat 会超过 cmd 的 8191 字符命令行上限，先打成一个 jar
Invoke-Tool jar cf "$work\classes.jar" -C "$work\classes" .
Invoke-Tool "$bt\d8.bat" --min-api 28 --lib $android --output "$work\dex" "$work\classes.jar"

# 4. 打包、对齐、签名
Invoke-Tool "$bt\aapt2.exe" compile --dir "$proj\res" -o "$work\res.zip"
# Windows 版 aapt2 的 -A 会把子目录写成反斜杠路径，安卓上读不到；资源改用 jar 加入（正斜杠）
Invoke-Tool "$bt\aapt2.exe" link -o "$work\unsigned.apk" --manifest "$proj\AndroidManifest.xml" -I $android "$work\res.zip"
Push-Location "$work\dex"
try { Invoke-Tool jar uf "$work\unsigned.apk" classes.dex } finally { Pop-Location }
Push-Location $work
try { Invoke-Tool jar uf "$work\unsigned.apk" assets } finally { Pop-Location }
# 原生渲染用的 TTF（Inter / Noto Sans SC，300/400/600）：不压缩存放，Font.Builder 直接映射，不进应用堆
$fontStage = "$work\fontstage\assets\fonts"
New-Item -ItemType Directory -Force $fontStage | Out-Null
Copy-Item "$proj\fonts\*.ttf" $fontStage
Push-Location "$work\fontstage"
try { Invoke-Tool jar --update --no-compress --file "$work\unsigned.apk" assets/fonts } finally { Pop-Location }
Invoke-Tool "$bt\zipalign.exe" -f -p 4 "$work\unsigned.apk" "$work\aligned.apk"

if (-not $KeyStore) {
    Write-Warning "没有给签名密钥（-KeyStore 或 TESSERA_KEYSTORE），输出的是未签名的包：$work\aligned.apk"
    return
}
if (-not $env:TESSERA_KEYSTORE_PASS) { throw "请设置环境变量 TESSERA_KEYSTORE_PASS（签名密钥的密码）" }
Invoke-Tool "$bt\apksigner.bat" sign --ks $KeyStore --ks-pass env:TESSERA_KEYSTORE_PASS --out "$Out\Tessera.apk" "$work\aligned.apk"
$apk = Get-Item "$Out\Tessera.apk"
'{0}  {1:N0} 字节' -f $apk.FullName, $apk.Length
