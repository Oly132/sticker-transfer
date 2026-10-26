param(
    [Parameter(Mandatory=$true)][string]$Tag,
    [string]$Apk,
    [string]$NotesFile,
    [string]$GitHubCli = 'gh',
    [switch]$Mandatory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($Tag -notmatch '^v[0-9]+\.[0-9]+(?:\.[0-9]+)?$') { throw 'Use a release tag such as v4.8.0.' }
if (!$Apk) {
    Push-Location $projectRoot
    try {
        & .\gradlew.bat testDebugUnitTest lintDebug assemblePrototype
        if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
    } finally { Pop-Location }
    $Apk = Join-Path $projectRoot 'app/build/outputs/apk/prototype/app-prototype.apk'
}
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (!$tools) { throw 'Android build tools are required to validate the APK.' }
$signature = & (Join-Path $tools.FullName 'apksigner.bat') verify --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$expected = 'b3187d034b8c8f9d0c8010c7e5b715e58ba85def9b70fd49e7b572c43581c430'
if (!($signature -match [regex]::Escape($expected))) { throw 'Signing key differs from the installed official app. Do not publish this APK.' }
$badging = & (Join-Path $tools.FullName 'aapt2.exe') dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the APK metadata.' }
$line = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
$packageMatch = [regex]::Match($line, "^package:\s+name='(?<package>[^']+)'\s+versionCode='(?<code>[0-9]+)'\s+versionName='(?<version>[^']+)'")
if (!$packageMatch.Success -or $packageMatch.Groups['package'].Value -cne 'com.example.stickerpackbuilder.samsunglab') { throw 'Unexpected app package.' }
$versionCode = [long]$packageMatch.Groups['code'].Value
$versionName = $packageMatch.Groups['version'].Value -replace '-samsung$',''
function Normalize-Version([string]$Value) { return (($Value.TrimStart('v').Split('.') + @('0','0') | Select-Object -First 3) -join '.') }
if ((Normalize-Version $Tag) -ne (Normalize-Version $versionName)) { throw 'Release tag does not match the APK version.' }
$latestRaw = & $GitHubCli api repos/Oly132/sticker-transfer/releases/latest 2>$null
if ($LASTEXITCODE -eq 0) {
    $latest = $latestRaw | ConvertFrom-Json
    $previous = $latest.tag_name.TrimStart('v')
    $oldParts = ($previous.Split('.') + @('0','0') | Select-Object -First 3) -join '.'
    $newParts = ($Tag.TrimStart('v').Split('.') + @('0','0') | Select-Object -First 3) -join '.'
    if ([version]$newParts -le [version]$oldParts) { throw 'The new release tag must be higher than the current release.' }
    if ($latest.body -match 'android-version-code:\s*([0-9]+)' -and $versionCode -le [long]$Matches[1]) {
        throw 'Increase Android versionCode before publishing. Otherwise Android cannot upgrade installed copies.'
    }
}
$releaseDir = Join-Path $projectRoot 'release-output'
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$releaseApk = Join-Path $releaseDir 'StickerTransfer.apk'
Copy-Item -LiteralPath $apkPath -Destination $releaseApk
$hash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
$checksum = Join-Path $releaseDir 'SHA256SUMS.txt'
[IO.File]::WriteAllText($checksum, "$hash  StickerTransfer.apk`n", [Text.UTF8Encoding]::new($false))
if (!$NotesFile) {
    $NotesFile = Join-Path $releaseDir 'release-notes.md'
    [IO.File]::WriteAllText($NotesFile, "Sticker Transfer $Tag (Android version code $versionCode).`n`nInstall over the existing app to keep your stickers and connections. Android requires installation confirmation.`n", [Text.UTF8Encoding]::new($false))
}
$preparedNotes = Join-Path $releaseDir 'published-notes.md'
$notesText = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $NotesFile).Path)
if ($Mandatory -and !$notesText.Contains('<!-- mandatory-update -->')) { $notesText += "`n`n<!-- mandatory-update -->" }
[IO.File]::WriteAllText($preparedNotes, "$notesText`n`n<!-- android-version-code: $versionCode -->`n", [Text.UTF8Encoding]::new($false))
& $GitHubCli release create $Tag $releaseApk $checksum --repo Oly132/sticker-transfer --title "Sticker Transfer $Tag" --notes-file $preparedNotes --latest
if ($LASTEXITCODE -ne 0) { throw 'GitHub release publishing failed. Check the repository before retrying.' }
