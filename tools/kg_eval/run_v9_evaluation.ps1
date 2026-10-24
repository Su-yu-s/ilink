[CmdletBinding()]
param(
    [ValidateSet('dev20', 'full100')][string]$Mode = 'dev20',
    [ValidateRange(1, 3)][int]$Rounds = 1,
    [ValidateSet('development', 'formal')][string]$Purpose = 'development',
    # Optional comma separated case ids for -Mode dev20. The default set is frozen; pass an
    # explicit list only when a fix targets cases outside it (for example the M-class cases).
    [string]$Ids = '',
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($Purpose -eq 'formal' -and ($Mode -ne 'full100' -or $Rounds -ne 3)) {
    throw 'A formal run requires -Mode full100 -Rounds 3. The purpose tag does not replace evidence audit or acceptance review.'
}
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
$taskMaven = (Get-Command mvn -ErrorAction Stop).Source
if (Test-Path -LiteralPath $taskOutput) {
    throw "Output directory already exists. Choose a new directory; previous results will not be overwritten: $taskOutput"
}
$taskSnapshotHash = '6626db757253ced62d1e6887c728f9456661e1e01753fc5f43f8da1108093c3b:cfe594bce93e326676e0f206c7d22c892908863bf115cb75ee0139a7581954a8:a7b3bdf9e3e160b0fe904efaeeb79ff5c6720ecc3d15c4af2b3b7c346031d3a9'
$taskIds = 'E1,E10,E18,E19,E28,E31,E32,E42,T2,T6,T8,T20,T25,M2,M4,M7,U1,U12,U20,U35'
if (-not [string]::IsNullOrWhiteSpace($Ids)) {
    $taskIds = $Ids.Trim()
    $taskIdTokens = $taskIds -split ','
    foreach ($taskIdToken in $taskIdTokens) {
        if ($taskIdToken -notmatch '^[A-Z]+\d+$') {
            throw "Case ids must look like M4, T12 or E31 and be comma separated without spaces: $taskIdToken"
        }
    }
    if ($taskIdTokens.Count -ne ($taskIdTokens | Select-Object -Unique).Count) {
        throw "Duplicate case ids are not allowed: $taskIds"
    }
}
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Push-Location $taskWorkspace
try {
    for ($taskRound = 1; $taskRound -le $Rounds; $taskRound++) {
        $taskRoundOutput = Join-Path $taskOutput "round$taskRound"
        $taskLog = Join-Path $taskOutput "round$taskRound.log"
        $taskArgs = @(
            '-q', '-Dtest=GraphRagEvaluationTest', '-DkgEval=true', '-DkgEvalBatch=all',
            "-DkgEvalRound=$taskRound", "-DkgEvalPurpose=$Purpose",
            '-DkgEvalDate=2026-09-26',
            '-DkgStageDatabase=ilink_kg_b3_20260923a',
            '-Dspring.datasource.url=jdbc:mysql://localhost:3306/ilink_kg_b3_20260923a',
            '-Dapp.ai.model=qwen3.7-flash', '-Dapp.ai.temperature=0.3',
            '-Dapp.ai.embedding.model=qwen3.7-text-embedding-flash',
            "-DkgEvalSnapshotHash=$taskSnapshotHash", "-DkgEvalOutDir=$taskRoundOutput"
        )
        if ($taskRound -gt 1) {
            $taskFrozenManifest = Join-Path $taskOutput 'round1/manifest.json'
            $taskArgs += "-DkgEvalFrozenManifest=$taskFrozenManifest"
        }
        if ($Mode -eq 'dev20') { $taskArgs += "-DkgEvalIds=$taskIds" }
        $taskArgs += 'test'
        Write-Host "Starting $Mode round $taskRound/$Rounds; results: $taskRoundOutput"
        & $taskMaven @taskArgs *> $taskLog
        $taskExit = $LASTEXITCODE
        if ($taskExit -ne 0) {
            throw "Round $taskRound stopped (exit $taskExit). Keep its files and log: $taskLog"
        }
        Write-Host "Completed round $taskRound."
    }
} finally {
    Pop-Location
}
