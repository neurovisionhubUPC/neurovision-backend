# Explicit single-attempt recovery ledger helpers. Dot-sourcing defines functions only.

function Get-CapacityRecoveryDirectory {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'Recovery ledger storage is unavailable.' }
    return Join-Path $env:LOCALAPPDATA 'NeuroVision\diagnostics\telemetry-compose-capacity-recovery'
}

function Get-CapacityRecoveryMarkerState([string] $Path) {
    try {
        $item = Get-Item -LiteralPath $Path -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -ne 8) {
            return [pscustomobject]@{ Valid=$false; Hash=$null }
        }
        $bytes = [IO.File]::ReadAllBytes($Path)
        $expected = [Text.Encoding]::ASCII.GetBytes('consumed')
        if ($bytes.Length -ne $expected.Length) { return [pscustomobject]@{ Valid=$false; Hash=$null } }
        for ($i=0; $i -lt $bytes.Length; $i++) {
            if ($bytes[$i] -ne $expected[$i]) { return [pscustomobject]@{ Valid=$false; Hash=$null } }
        }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
        return [pscustomobject]@{ Valid=$true; Hash=$hash }
    } catch { return [pscustomobject]@{ Valid=$false; Hash=$null } }
}

function Assert-CapacityRecoveryMarkerHash([string] $Path, [string] $ExpectedHash) {
    $state = Get-CapacityRecoveryMarkerState $Path
    if (-not $state.Valid -or $state.Hash -cne $ExpectedHash) {
        throw 'The recovery marker is missing, invalid, or changed.'
    }
    return $true
}

function Assert-CapacityRecoveryMarkerUnchanged([string] $ExpectedHash, [scriptblock] $Probe) {
    $state = & $Probe
    if ($null -eq $state -or -not $state.Valid -or $state.Hash -cne $ExpectedHash) {
        throw 'The recovery marker is missing, invalid, or changed.'
    }
    return $true
}

function Initialize-CapacityRecoveryAttempt($Intent, [scriptblock] $MarkerProbe,
        [scriptblock] $LedgerProbe, [scriptblock] $ReserveIntent) {
    $first = & $MarkerProbe
    if ($null -eq $first -or -not $first.Valid -or $first.Hash -notmatch '^[0-9a-f]{64}$') {
        throw 'The recovery marker is missing, invalid, or changed.'
    }
    if ((& $LedgerProbe) -cne 'absent') { throw 'Recovery ledger is unavailable or already contains an attempt.' }
    Assert-CapacityRecoveryMarkerUnchanged $first.Hash $MarkerProbe | Out-Null
    if (-not (& $ReserveIntent $Intent)) { throw 'Recovery ledger is unavailable or already contains an attempt.' }
    return [pscustomobject]@{ Reserved=$true; MarkerHash=$first.Hash }
}

function Claim-CapacityRecoveryIntent([scriptblock] $CreateExclusive) {
    try { return [bool](& $CreateExclusive) } catch { return $false }
}

function Save-CapacityRecoveryOutcomeOrThrow([scriptblock] $SaveOutcome, $Outcome) {
    if (-not (& $SaveOutcome $Outcome)) { throw 'Recovery outcome could not be durably stored.' }
    return $true
}

function Invoke-CapacityRecoveryGuardedMutation([string] $ExpectedHash,
        [scriptblock] $MarkerProbe, [scriptblock] $Mutation) {
    Assert-CapacityRecoveryMarkerUnchanged $ExpectedHash $MarkerProbe | Out-Null
    return & $Mutation
}

function Protect-CapacityRecoveryText([string] $Text) {
    try {
        Add-Type -AssemblyName System.Security -ErrorAction Stop
        $plain = [Text.Encoding]::UTF8.GetBytes($Text)
        try {
            $cipher = [Security.Cryptography.ProtectedData]::Protect($plain, $null,
                [Security.Cryptography.DataProtectionScope]::CurrentUser)
            return [pscustomobject]@{ Available=$true; CipherText=[Convert]::ToBase64String($cipher) }
        } finally { [Array]::Clear($plain, 0, $plain.Length) }
    } catch { return [pscustomobject]@{ Available=$false; CipherText=$null } }
}

function Unprotect-CapacityRecoveryText([string] $CipherText) {
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $cipher = [Convert]::FromBase64String($CipherText)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect($cipher, $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { return [Text.Encoding]::UTF8.GetString($plain) }
    finally { [Array]::Clear($plain, 0, $plain.Length) }
}

function Test-CapacityRecoveryPath([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 'absent' }
    try {
        $item = Get-Item -LiteralPath $Path -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                $item.Length -lt 16 -or $item.Length -gt 1048576) { return 'corrupt' }
        $bytes = [IO.File]::ReadAllBytes($Path)
        try { [void](Unprotect-CapacityRecoveryText ([Convert]::ToBase64String($bytes))) }
        finally { [Array]::Clear($bytes, 0, $bytes.Length) }
        return 'existing'
    } catch { return 'corrupt' }
}

function Get-CapacityRecoveryLedgerState([string] $Directory) {
    $base = $env:LOCALAPPDATA
    if ([string]::IsNullOrWhiteSpace($base)) { return 'corrupt' }
    $current = $base
    foreach ($part in @('NeuroVision','diagnostics','telemetry-compose-capacity-recovery')) {
        $current = Join-Path $current $part
        if (-not (Test-Path -LiteralPath $current)) { return 'absent' }
        try {
            $item = Get-Item -LiteralPath $current -ErrorAction Stop
            if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return 'corrupt' }
        } catch { return 'corrupt' }
    }
    $intent = Test-CapacityRecoveryPath (Join-Path $Directory 'attempt.intent.dpapi')
    $outcome = Test-CapacityRecoveryPath (Join-Path $Directory 'attempt.outcome.dpapi')
    if ($intent -eq 'corrupt' -or $outcome -eq 'corrupt') { return 'corrupt' }
    if ($intent -eq 'existing' -or $outcome -eq 'existing') { return 'existing' }
    return 'absent'
}

function Write-CapacityRecoveryEncryptedCreateNew([string] $Path, [string] $Text) {
    $script:fileWrites++
    $protected = Protect-CapacityRecoveryText $Text
    if (-not $protected.Available) { return $false }
    $bytes = [Convert]::FromBase64String($protected.CipherText)
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
        return $true
    } catch { return $false }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Reserve-CapacityRecoveryIntent([string] $Directory, [string] $Text) {
    if ((Get-CapacityRecoveryLedgerState $Directory) -cne 'absent') { return $false }
    try {
        if (-not (Test-Path -LiteralPath $Directory)) { [IO.Directory]::CreateDirectory($Directory) | Out-Null }
        if ((Get-CapacityRecoveryLedgerState $Directory) -cne 'absent') { return $false }
        return Claim-CapacityRecoveryIntent { Write-CapacityRecoveryEncryptedCreateNew (Join-Path $Directory 'attempt.intent.dpapi') $Text }
    } catch { return $false }
}

function Save-CapacityRecoveryOutcome([string] $Directory, [string] $Text) {
    if ((Test-CapacityRecoveryPath (Join-Path $Directory 'attempt.intent.dpapi')) -ne 'existing') { return $false }
    if ((Test-CapacityRecoveryPath (Join-Path $Directory 'attempt.outcome.dpapi')) -ne 'absent') { return $false }
    return Claim-CapacityRecoveryIntent { Write-CapacityRecoveryEncryptedCreateNew (Join-Path $Directory 'attempt.outcome.dpapi') $Text }
}
