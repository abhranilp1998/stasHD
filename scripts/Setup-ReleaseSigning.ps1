param([string]$SigningDirectory = (Join-Path $env:USERPROFILE '.stashd\signing'))
$ErrorActionPreference = 'Stop'
$keystore = Join-Path $SigningDirectory 'stashd-release.p12'
$passwordFile = Join-Path $SigningDirectory 'password.dpapi'
if (!(Test-Path -LiteralPath $keystore)) {
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $principal = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $SigningDirectory /inheritance:r /grant:r "${principal}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect the signing directory.' }
    $password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile
    $env:STASHD_KEYTOOL_PASSWORD = $password
    try {
        & keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias stashd -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Stashd' -storepass:env STASHD_KEYTOOL_PASSWORD -keypass:env STASHD_KEYTOOL_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
    } finally { Remove-Item Env:STASHD_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
}
if (!(Test-Path -LiteralPath $passwordFile)) { throw 'Existing key found without its protected password. Nothing was overwritten.' }
$secure = (Get-Content -Raw -LiteralPath $passwordFile).Trim() | ConvertTo-SecureString
$env:STASHD_KEYTOOL_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
try {
    $certificate = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs\release-certificate.pem'
    & keytool -exportcert -rfc -keystore $keystore -alias stashd -storepass:env STASHD_KEYTOOL_PASSWORD -file $certificate
    if ($LASTEXITCODE -ne 0) { throw 'Could not export the public release certificate.' }
    & keytool -list -v -keystore $keystore -alias stashd -storepass:env STASHD_KEYTOOL_PASSWORD | Select-String 'SHA1:|SHA256:'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the release certificate.' }
    Write-Output "Permanent signing key: $keystore"
    Write-Output 'The password is protected with Windows DPAPI for this Windows account. Back up the key and save its password in your password manager before relying on releases.'
} finally { Remove-Item Env:STASHD_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue; $secure = $null }
