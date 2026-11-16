#requires -Version 7.2
param([string]$SigningDirectory = (Join-Path $env:USERPROFILE '.stashd\signing'))
$ErrorActionPreference = 'Stop'
$secure = (Get-Content -Raw -LiteralPath (Join-Path $SigningDirectory 'password.dpapi')).Trim() | ConvertTo-SecureString
Set-Clipboard -Value ([System.Net.NetworkCredential]::new('', $secure).Password)
Write-Output 'Signing password copied. Save it in your password manager with a backup of stashd-release.p12, then clear the clipboard. Do not send it in chat.'
