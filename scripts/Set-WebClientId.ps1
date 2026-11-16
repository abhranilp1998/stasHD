#requires -Version 7.2
param([Parameter(Mandatory)][string]$ClientId)
$ErrorActionPreference = 'Stop'
$ClientId = $ClientId.Trim()
if ($ClientId -notmatch '^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$') { throw 'Paste the Client ID of the Web application client, not a client secret or Android client ID.' }
$configDirectory = Join-Path $env:USERPROFILE '.stashd'
New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
$ClientId | Set-Content -LiteralPath (Join-Path $configDirectory 'oauth-web-client-id.txt') -Encoding utf8NoBOM
Write-Output 'Web client ID saved locally for release builds. No client secret is used.'
