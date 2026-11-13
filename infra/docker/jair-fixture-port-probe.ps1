function Get-JairPortBindingState {
    param($Bindings)
    $entries = @($Bindings | Where-Object { $null -ne $_ })
    if (!$entries.Count) { return 'absent' }
    if ($entries.Count -eq 1 -and $entries[0].HostIp -ceq '127.0.0.1' -and
            $entries[0].HostPort -ceq '18081') { return 'expected' }
    'unexpected'
}

function Get-JairDiscoveryResult {
    param([int]$ExitCode, [string[]]$Lines)
    $status = $null
    if ($Lines.Count -and $Lines[-1] -cmatch '^[1-5][0-9]{2}$') { $status = [int]$Lines[-1] }
    $match = $false
    $category = switch ($ExitCode) {
        0 { if ($null -ne $status) { 'response' } else { 'probe_error' } }
        6 { 'dns_error' }
        7 { 'connection_error' }
        28 { 'timeout' }
        default { 'probe_error' }
    }
    if ($ExitCode -eq 0 -and $status -eq 200 -and $Lines.Count -ge 2) {
        try {
            $json = ConvertFrom-Json -InputObject ($Lines[0..($Lines.Count - 2)] -join "`n") -AsHashtable -NoEnumerate -ErrorAction Stop
            $match = $json -is [System.Collections.IDictionary] -and
                $json.issuer -ceq 'http://oidc-fixture:8080' -and $json.jwks_uri -ceq 'http://oidc-fixture:8080/jwks'
        } catch { $category = 'invalid_json' }
    }
    [pscustomobject]@{ category = $category; http_status = $status; discovery_matches = $match }
}

function Get-JairFixturePortDiagnostic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidatePattern('^nv-jair-(port|native)-[a-z0-9-]+$')][string]$Project,
        [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$FixtureId
    )

    $PSNativeCommandUseErrorActionPreference = $false
    $endpoint = docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $endpoint -cne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
        throw 'Local Docker Desktop Linux context required'
    }
    $raw = docker --context desktop-linux container inspect $FixtureId 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Fixture inspection failed' }
    $items = ConvertFrom-Json -InputObject ($raw -join "`n") -AsHashtable -NoEnumerate
    if ($items -isnot [array] -or $items.Count -ne 1) { throw 'Unexpected fixture inventory' }
    $fixture = $items[0]
    $name = "$Project-neurovision"
    $networks = $fixture.NetworkSettings.Networks
    if ($fixture.Id -cne $FixtureId -or !$fixture.State.Running -or
            $fixture.Config.Labels['com.docker.compose.project'] -cne $Project -or
            $fixture.Config.Labels['com.docker.compose.service'] -cne 'oidc-fixture' -or
            $fixture.Image -cne 'sha256:9fa424e2f0fac1b17115692abed3f8c9617280c06a6ebdc4384becb7eb62f981' -or
            $networks.Count -ne 1 -or !$networks.Contains($name)) { throw 'Fixture isolation guard failed' }
    $raw = docker --context desktop-linux network inspect $networks[$name].NetworkID 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Network inspection failed' }
    $items = ConvertFrom-Json -InputObject ($raw -join "`n") -AsHashtable -NoEnumerate
    if ($items -isnot [array] -or $items.Count -ne 1 -or !$items[0].Internal -or
            $items[0].Name -cne $name -or $items[0].Labels['com.docker.compose.project'] -cne $Project) {
        throw 'Network isolation guard failed'
    }

    $requested = Get-JairPortBindingState $fixture.HostConfig.PortBindings.'8080/tcp'
    $effective = Get-JairPortBindingState $fixture.NetworkSettings.Ports.'8080/tcp'
    $listener = 'probe_error'
    try {
        $addresses = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object LocalPort -eq 18081 | Select-Object -ExpandProperty LocalAddress)
        $listener = if ($addresses -contains '127.0.0.1') { 'loopback' }
            elseif ($addresses.Count) { 'other_address' } else { 'absent' }
    } catch { }

    $path = '/.well-known/openid-configuration'
    $curlArgs = @('-q', '--noproxy', '*', '--silent', '--connect-timeout', '2', '--max-time', '4',
        '--max-filesize', '8192', '--write-out', '\n%{http_code}')
    $inside = @(docker --context desktop-linux exec $FixtureId curl @curlArgs --retry 5 --retry-delay 1 `
        --retry-connrefused --retry-max-time 10 "http://oidc-fixture:8080$path" 2>$null)
    $internal = Get-JairDiscoveryResult -ExitCode $LASTEXITCODE -Lines $inside
    # Do not probe an unexpected binding or a listener that appeared on an unpublished port.
    $windows = if ($requested -eq 'unexpected' -or $effective -eq 'unexpected' -or
            ($effective -eq 'absent' -and $listener -ne 'absent')) {
        [pscustomobject]@{ category = 'skipped_unverified_listener'; http_status = $null; discovery_matches = $false }
    } else {
        $outside = @(curl.exe @curlArgs "http://127.0.0.1:18081$path" 2>$null)
        Get-JairDiscoveryResult -ExitCode $LASTEXITCODE -Lines $outside
    }
    [pscustomobject]@{ requested = $requested; effective = $effective; windows_listener = $listener
        internal_http = $internal; windows_http = $windows }
}
