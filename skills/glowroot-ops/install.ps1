# Install glowroot-ops globally (PowerShell)
# Usage: .\install.ps1

$ErrorActionPreference = "Stop"

$SkillName = "glowroot-ops"
$RepoTree = "https://github.com/glowroot/glowroot/tree/main/skills/glowroot-ops"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Install-Manual {
    param([string]$TargetRoot)
    $dest = Join-Path $TargetRoot $SkillName
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Get-ChildItem -Path $ScriptDir -Exclude "install.ps1","install.sh" | Copy-Item -Recurse -Force -Destination $dest
    Write-Host "  -> $dest"
}

Write-Host "glowroot-ops installer"
Write-Host ""

if (Get-Command npx -ErrorAction SilentlyContinue) {
    Write-Host "[1/2] npx skills add (global)..."
    $npxArgs = @("--yes", "skills@latest", "add", $RepoTree, "--skill", $SkillName, "--global", "--yes", "--copy")
    & npx @npxArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  npx failed - manual copy"
        Install-Manual (Join-Path $env:USERPROFILE ".cursor\skills")
        Install-Manual (Join-Path $env:USERPROFILE ".claude\skills")
        Install-Manual (Join-Path $env:USERPROFILE ".agents\skills")
    } else {
        Write-Host "  npx install OK"
    }
} else {
    Write-Host "[1/2] npx not found - manual copy"
    Install-Manual (Join-Path $env:USERPROFILE ".cursor\skills")
    Install-Manual (Join-Path $env:USERPROFILE ".claude\skills")
    Install-Manual (Join-Path $env:USERPROFILE ".agents\skills")
}

Write-Host "[2/2] Cursor slash command..."
$cmdDir = Join-Path $env:USERPROFILE ".cursor\commands"
$cmdFile = Join-Path $cmdDir "$SkillName.md"
New-Item -ItemType Directory -Force -Path $cmdDir | Out-Null
Copy-Item -Force (Join-Path $ScriptDir "integrations\cursor-command.md") $cmdFile
Write-Host "  -> $cmdFile"

Write-Host ""
Write-Host "Done."
Write-Host "  Cursor: /glowroot-ops then your question"
Write-Host "  Other:  paste PROMPT.md"
Write-Host "  Wiki:   https://github.com/glowroot/glowroot/wiki"
