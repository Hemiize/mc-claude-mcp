<#
.SYNOPSIS
  Registers the Claude MCP Minecraft server with Claude Code (user scope).

.PARAMETER ConfigDir
  The claudemcp config folder of the Minecraft instance (contains token.txt).
  Default: the Prism instance "Fabric 26.3", falling back to the dev run folder of this repo.

.PARAMETER Name
  Name of the MCP server entry in Claude Code (default: minecraft).

.EXAMPLE
  .\register.ps1
  .\register.ps1 -ConfigDir "C:\Claude\mc-claude-mcp\run\config\claudemcp"
#>
param(
	[string]$ConfigDir = "",
	[string]$Name = "minecraft"
)

$ErrorActionPreference = "Stop"

if (-not $ConfigDir) {
	$candidates = @(
		(Join-Path $env:APPDATA "PrismLauncher\instances\Fabric 26.3\minecraft\config\claudemcp"),
		(Join-Path $env:APPDATA "PrismLauncher\instances\Fabric 26.3\.minecraft\config\claudemcp"),
		(Join-Path $PSScriptRoot "run\config\claudemcp")
	)
	foreach ($c in $candidates) {
		if (Test-Path (Join-Path $c "token.txt")) { $ConfigDir = $c; break }
	}
	if (-not $ConfigDir) {
		throw "No token.txt found. Start Minecraft with the mod once, or pass -ConfigDir. Looked in:`n  " + ($candidates -join "`n  ")
	}
}

$tokenFile = Join-Path $ConfigDir "token.txt"
$configFile = Join-Path $ConfigDir "config.json"
$token = (Get-Content $tokenFile -Raw).Trim()
$port = 25599
$hostName = "127.0.0.1"
if (Test-Path $configFile) {
	$cfg = Get-Content $configFile -Raw | ConvertFrom-Json
	if ($cfg.port) { $port = $cfg.port }
	if ($cfg.host) { $hostName = $cfg.host }
}
$url = "http://${hostName}:${port}/mcp"

if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
	throw "The 'claude' CLI was not found in PATH."
}

Write-Host "Config:  $ConfigDir"
Write-Host "Server:  $url"

# replace an existing entry with the same name
try { claude mcp remove $Name -s user 2>$null | Out-Null } catch {}
claude mcp add --scope user --transport http $Name $url --header "Authorization: Bearer $token"
if ($LASTEXITCODE -ne 0) { throw "claude mcp add failed" }

Write-Host ""
Write-Host "Registered MCP server '$Name'. Restart Claude Code (or run /mcp) while Minecraft is running."
