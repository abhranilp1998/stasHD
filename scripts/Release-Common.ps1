#requires -Version 7.2
$ErrorActionPreference = 'Stop'

function Get-StashdVersion {
    $buildFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\build.gradle.kts'
    $match = [regex]::Match((Get-Content -Raw -LiteralPath $buildFile), 'versionName = "([^"]+)"')
    if (!$match.Success) { throw 'Cannot read versionName.' }
    return $match.Groups[1].Value
}

function Get-StashdBuildTool([string]$Name) {
    $sdkDirectory = $env:ANDROID_HOME
    if (!$sdkDirectory) { $sdkDirectory = $env:ANDROID_SDK_ROOT }
    if (!$sdkDirectory) {
        $local = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
        $sdkLine = Get-Content -LiteralPath $local | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
        $sdkDirectory = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
    }
    $candidate = Get-ChildItem -LiteralPath (Join-Path $sdkDirectory 'build-tools') -Directory |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$candidate) { throw "Android build tool $Name is missing. Install Android SDK build-tools." }
    return $candidate
}

function Enable-StashdSigning([string]$SigningDirectory) {
    $keyPath = Join-Path $SigningDirectory 'stashd-release.p12'
    $protectedPassword = Join-Path $SigningDirectory 'password.dpapi'
    if (!(Test-Path -LiteralPath $keyPath) -or !(Test-Path -LiteralPath $protectedPassword)) {
        throw 'Run scripts/Setup-ReleaseSigning.ps1 once to create the permanent key.'
    }
    $secure = (Get-Content -Raw -LiteralPath $protectedPassword).Trim() | ConvertTo-SecureString
    $env:STASHD_RELEASE_STORE_FILE = $keyPath
    $env:STASHD_RELEASE_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
    $env:STASHD_RELEASE_KEY_ALIAS = 'stashd'
    $env:STASHD_RELEASE_KEY_PASSWORD = $env:STASHD_RELEASE_STORE_PASSWORD
}

function Disable-StashdSigning {
    'STASHD_RELEASE_STORE_FILE','STASHD_RELEASE_STORE_PASSWORD','STASHD_RELEASE_KEY_ALIAS','STASHD_RELEASE_KEY_PASSWORD' |
        ForEach-Object { Remove-Item -LiteralPath "Env:$_" -ErrorAction SilentlyContinue }
}
