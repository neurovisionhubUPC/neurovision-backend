function Get-JairReadinessProbe {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateRange(18081, 18085)]
        [int]$Port
    )

    $status = $null
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/q/health/ready" `
            -NoProxy -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 2 -ErrorAction Stop
        $status = [int]$response.StatusCode
    } catch {
        $category = 'probe_error'
        for ($e = $_.Exception; $null -ne $e; $e = $e.InnerException) {
            if ($e -is [System.OperationCanceledException] -or $e -is [System.TimeoutException]) {
                $category = 'timeout'
                break
            }
            if ($e -is [System.Net.Sockets.SocketException]) {
                $category = if ($e.SocketErrorCode -eq 'ConnectionRefused') { 'connection_refused' }
                    elseif ($e.SocketErrorCode -eq 'TimedOut') { 'timeout' }
                    else { 'connection_error' }
                break
            }
            if ($e -is [System.Net.Http.HttpRequestException]) {
                if ($null -ne $e.StatusCode) {
                    $status = [int]$e.StatusCode
                    $category = 'http_status'
                    break
                }
                if ($e.HttpRequestError -eq 'ConnectionError') { $category = 'connection_error' }
            }
        }
        return [pscustomobject]@{ category = $category; http_status = $status }
    }

    ConvertTo-JairReadinessResult -Status $status -Content $response.Content
}

function ConvertTo-JairReadinessResult {
    param([int]$Status, [string]$Content)
    if ($Status -ne 200) {
        return [pscustomobject]@{ category = 'http_status'; http_status = $status }
    }
    try {
        $body = ConvertFrom-Json -InputObject $Content -AsHashtable -NoEnumerate -ErrorAction Stop
    } catch {
        return [pscustomobject]@{ category = 'invalid_json'; http_status = $status }
    }
    $category = if ($body -isnot [System.Collections.IDictionary] -or $body.status -isnot [string]) {
        'invalid_readiness'
    } elseif ($body.status -ceq 'UP') {
        'up'
    } else {
        'not_up'
    }
    [pscustomobject]@{ category = $category; http_status = $status }
}

function Get-JairReadinessComparison {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidatePattern('^nv-jair-native-[a-z0-9-]+$')]
        [string]$Project,
        [Parameter(Mandatory)]
        [ValidatePattern('^[a-f0-9]{64}$')]
        [string]$FixtureId
    )

    $endpoint = docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $endpoint -cne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
        throw 'Local Docker Desktop Linux context required'
    }
    $raw = docker --context desktop-linux container inspect $FixtureId 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Fixture inspection failed' }
    $fixtures = ConvertFrom-Json -InputObject ($raw -join "`n") -AsHashtable -NoEnumerate
    if ($fixtures -isnot [array] -or $fixtures.Count -ne 1) { throw 'Unexpected fixture inventory' }
    $fixture = $fixtures[0]
    $network = "$Project-neurovision"
    if ($fixture.Id -cne $FixtureId -or !$fixture.State.Running -or
            $fixture.Config.Labels['com.docker.compose.project'] -cne $Project -or
            $fixture.Config.Labels['com.docker.compose.service'] -cne 'oidc-fixture' -or
            $fixture.Image -cne 'sha256:9fa424e2f0fac1b17115692abed3f8c9617280c06a6ebdc4384becb7eb62f981' -or
            $fixture.HostConfig.PortBindings.Count -ne 0 -or
            $fixture.NetworkSettings.Networks.Count -ne 1 -or
            !$fixture.NetworkSettings.Networks.Contains($network)) {
        throw 'Fixture ownership or isolation guard failed'
    }
    $rawNetwork = docker --context desktop-linux network inspect $fixture.NetworkSettings.Networks[$network].NetworkID 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Network inspection failed' }
    $networks = ConvertFrom-Json -InputObject ($rawNetwork -join "`n") -AsHashtable -NoEnumerate
    if ($networks -isnot [array] -or $networks.Count -ne 1) { throw 'Unexpected network inventory' }
    $net = $networks[0]
    if ($net.Name -cne $network -or !$net.Internal -or $net.Labels['com.docker.compose.project'] -cne $Project) {
        throw 'Network ownership or isolation guard failed'
    }

    $ports = [ordered]@{
        'iam-service' = 18081; 'profile-service' = 18082; 'clinical-service' = 18083
        'telemetry-service' = 18084; 'analytics-service' = 18085
    }
    foreach ($service in $ports.Keys) {
        $uri = "http://${service}:8080/q/health/ready"
        $lines = @(docker --context desktop-linux exec $FixtureId curl -q --noproxy '*' --silent `
            --connect-timeout 2 --max-time 2 --max-filesize 65536 --write-out '\n%{http_code}' $uri 2>$null)
        $code = $LASTEXITCODE
        $internal = if ($code -eq 0 -and $lines.Count -ge 1 -and $lines[-1] -cmatch '^[1-5][0-9]{2}$') {
            $body = if ($lines.Count -gt 1) { $lines[0..($lines.Count - 2)] -join "`n" } else { '' }
            ConvertTo-JairReadinessResult -Status ([int]$lines[-1]) -Content $body
        } else {
            $category = switch ($code) {
                6 { 'dns_error' }
                7 { 'connection_error' }
                28 { 'timeout' }
                default { 'probe_error' }
            }
            [pscustomobject]@{ category = $category; http_status = $null }
        }
        [pscustomobject]@{
            service = $service
            internal_dns = $internal
            windows_loopback = Get-JairReadinessProbe -Port $ports[$service]
        }
    }
}
