#requires -Version 7.2
param([switch]$Demo, [switch]$Publish, [string]$GitHubCli = 'gh')
. (Join-Path $PSScriptRoot 'Release-Common.ps1')
$projectDirectory = Split-Path $PSScriptRoot -Parent
Push-Location $projectDirectory
try {
    if (git status --porcelain) { throw 'Commit and push the reviewed source before making a release.' }
    & $GitHubCli auth status
    if ($LASTEXITCODE -ne 0) { throw 'Sign in through gh auth login --web --git-protocol https. Do not paste tokens into chat.' }
    & (Join-Path $PSScriptRoot 'Build-Release.ps1') -Demo:$Demo
    $version = (Get-StashdVersion) + $(if ($Demo) { '-demo' } else { '' })
    $output = Join-Path $projectDirectory "output\releases\v$version"
    $notes = Join-Path $projectDirectory $(if ($Demo) { 'docs\releases\demo-rc.md' } else { 'docs\releases\drive-rc.md' })
    $arguments = @('release','create',"v$version",'--repo','abhranilp1998/stasHD','--target',(git rev-parse HEAD),
        '--title',"Stashd $version",'--notes-file',$notes)
    if (!$Publish) { $arguments += '--draft' }
    if ($version.Contains('-')) { $arguments += '--prerelease' }
    $arguments += @(Get-ChildItem -LiteralPath $output -File | ForEach-Object { $_.FullName })
    & $GitHubCli @arguments
    if ($LASTEXITCODE -ne 0) { throw 'GitHub release creation failed; the verified local APK is still available.' }
} finally { Pop-Location }
