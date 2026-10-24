[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InputDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskInput = [IO.Path]::GetFullPath($InputDirectory)
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
$taskLog = $taskOutput + '.log'
if (Test-Path -LiteralPath $taskOutput) { throw "Output directory already exists: $taskOutput" }
if (Test-Path -LiteralPath $taskLog) { throw "Review log already exists: $taskLog" }
if (-not (Test-Path -LiteralPath (Join-Path $taskInput 'manifest.json'))) { throw 'Input must be a saved round directory with manifest.json.' }
$taskMaven = (Get-Command mvn -ErrorAction Stop).Source
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskOutput)) -Force | Out-Null
Push-Location $taskWorkspace
try {
    $taskArgs = @('-q', '-Dtest=GraphRagSavedAnswerReviewTest', '-DkgEval=false', '-DkgReviewSaved=true',
        "-DkgReviewInput=$taskInput", "-DkgReviewOutput=$taskOutput", 'test')
    & $taskMaven @taskArgs *> $taskLog
    if ($LASTEXITCODE -ne 0) { throw "Offline review stopped. Keep the log: $taskLog" }
    Write-Host "Saved answers reviewed without new model requests: $taskOutput"
} finally {
    Pop-Location
}
