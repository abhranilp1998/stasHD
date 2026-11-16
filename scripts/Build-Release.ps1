#requires -Version 7.2
param(
    [switch]$Demo,
    [switch]$SkipChecks,
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.stashd\signing')
)
. (Join-Path $PSScriptRoot 'Release-Common.ps1')
$projectDirectory = Split-Path $PSScriptRoot -Parent
Push-Location $projectDirectory
$previousClientId = $env:STASHD_WEB_CLIENT_ID
try {
    Enable-StashdSigning $SigningDirectory
    $savedClientId = Join-Path $env:USERPROFILE '.stashd\oauth-web-client-id.txt'
    if (!$Demo -and !$env:STASHD_WEB_CLIENT_ID -and (Test-Path -LiteralPath $savedClientId)) {
        $env:STASHD_WEB_CLIENT_ID = (Get-Content -Raw -LiteralPath $savedClientId).Trim()
    }
    $variant = if ($Demo) { 'demoRelease' } else { 'release' }
    $task = if ($Demo) { 'assembleDemoRelease' } else { 'assembleRelease' }
    $checks = if ($SkipChecks) { @() } else { @('testDebugUnitTest', 'lintDebug') }
    & .\gradlew.bat @checks $task --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed; no release package was produced.' }

    $apk = Join-Path $projectDirectory "app\build\outputs\apk\$variant\app-$variant.apk"
    if (!(Test-Path -LiteralPath $apk)) { throw "Expected signed APK is missing: $apk" }
    $signature = & (Get-StashdBuildTool 'apksigner.bat') verify --verbose --print-certs --min-sdk-version 26 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signature | Write-Output
    $certificate = Join-Path $projectDirectory 'docs\release-certificate.pem'
    $der = [Convert]::FromBase64String(((Get-Content -Raw -LiteralPath $certificate) -replace '-----[^-]+-----','' -replace '\s',''))
    $expectedDigest = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($der)).ToLowerInvariant()
    $actualDigest = [regex]::Match(($signature -join "`n"), '(?im)^(?:Signer #\d+|V\d(?:\.\d+)? Signer): certificate SHA-256 digest: ([a-fA-F0-9]+)\s*$').Groups[1].Value.ToLowerInvariant()
    if (($signature -join "`n") -notmatch 'Number of signers: 1\b') { throw 'The release APK must have exactly one signer.' }
    if ($actualDigest -ne $expectedDigest) { throw 'APK is not signed by the pinned permanent Stashd certificate.' }
    & (Get-StashdBuildTool 'zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }
    & python (Join-Path $PSScriptRoot 'verify_apk_native.py') $apk
    if ($LASTEXITCODE -ne 0) { throw 'Native library ELF alignment check failed.' }
    $badging = & (Get-StashdBuildTool 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the APK manifest.' }
    $packageName = if ($Demo) { 'com.abhranil.stashd.demo' } else { 'com.abhranil.stashd' }
    if ($badging[0] -notmatch "name='$([regex]::Escape($packageName))'") { throw 'Unexpected APK package name.' }

    $version = (Get-StashdVersion) + $(if ($Demo) { '-demo' } else { '' })
    $output = Join-Path $projectDirectory "output\releases\v$version"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $assetName = "Stashd-v$version.apk"
    Copy-Item -LiteralPath $apk -Destination (Join-Path $output $assetName)
    Copy-Item -LiteralPath $certificate -Destination (Join-Path $output 'release-certificate.pem')
    $apkHash = (Get-FileHash -LiteralPath (Join-Path $output $assetName) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$apkHash  $assetName" | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding utf8NoBOM
    [ordered]@{
        version = $version; packageName = $packageName; commit = (git rev-parse HEAD)
        certificateSha256 = $actualDigest; apkSha256 = $apkHash
        unitTestsAndLintRequested = !$SkipChecks; demo = [bool]$Demo
        sourceDirty = [bool](git status --porcelain)
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'build-info.json') -Encoding utf8NoBOM
    Write-Output "Verified release package: $output"
} finally { Disable-StashdSigning; $env:STASHD_WEB_CLIENT_ID = $previousClientId; Pop-Location }
