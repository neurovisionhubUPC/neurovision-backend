[CmdletBinding()]
param(
    [switch] $RunPkce,
    [switch] $CheckNativeRuntime,
    [switch] $VerifyClinicalUnavailable,
    [ValidateSet('PATIENT', 'DOCTOR')][string] $Role = 'PATIENT'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$project = 'analytics-ca13c-native-20260925'
$overlay = Join-Path $root 'infra/docker/analytics-native-real-clinical.compose.yaml'
$fixture = Join-Path $root 'analytics-service/src/test/resources/fixtures/telemetry/v1/accepted-event-wire.json'
$fixtureSha256 = 'ed58c80f41d831f7c4926bd83bbb5469f0cefc4b035276ccd242b150eece7779'
$envFile = Join-Path $root '.env'
$redirect = 'http://localhost:5173/auth/callback'
$compose = @('compose', '-p', $project, '-f', (Join-Path $root 'compose.yaml'), '-f', $overlay)
$nativeImage = 'neurovision/analytics-service:compose-native'
$expectedNativeImageId = 'sha256:4df5f6b03adb16b06539d8cca7dfd3a98724174257d054f4ce9f398908c44abf'

function Assert-LocalSetup {
    $script:substage = 'local inputs'
    foreach ($path in @($overlay, $fixture, $envFile)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A required local input is missing.' }
    }
    $script:substage = 'Compose model'
    $modelJson = (Invoke-Compose @('config', '--format', 'json')) -join "`n"
    try { $model = ConvertFrom-Json -InputObject $modelJson }
    catch { throw 'The effective isolated Compose model is invalid.' }
    Assert-EffectiveComposeModel $model
    $script:substage = 'native image'
    Assert-LocalNativeImage
    $script:substage = 'synthetic fixture'
    $event = Read-SyntheticFixture
    if ($event.schemaVersion -ne 1 -or @($event.gazeSamples).Count -ne 1 -or
            @($event.gameplaySamples).Count -ne 1) {
        throw 'The checked-in synthetic V1 event fixture is unexpected.'
    }
    $script:substage = 'public Cognito configuration'
    $pool = Get-PublicSetting 'COGNITO_USER_POOL_ID'
    $issuer = (Get-PublicSetting 'COGNITO_ISSUER_URL').TrimEnd('/')
    $client = Get-PublicSetting 'COGNITO_CLIENT_ID'
    if ($pool -cnotmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $issuer -cne "https://cognito-idp.us-east-1.amazonaws.com/$pool" -or
            $client -notmatch '^[A-Za-z0-9]+$') {
        throw 'The public Cognito configuration is not the expected development pool.'
    }
    Write-Host 'CA13C_PREFLIGHT_OK: isolated model, loopback bindings, public Cognito shape, and synthetic fixture validated; no remote request made.'
    return @{ Issuer = $issuer; Client = $client }
}

function Assert-EffectiveComposeModel($model) {
    if ($model.name -cne $project) { throw 'The effective Compose project is unexpected.' }
    foreach ($name in @('clinical-postgresql', 'analytics-mongodb', 'kafka', 'clinical-service', 'analytics-service')) {
        $service = $model.services.$name
        if ($null -eq $service) { throw 'An expected isolated service is missing.' }
        $ports = @($service.ports | Where-Object { $null -ne $_ })
        if ($name -in @('clinical-service', 'analytics-service')) {
            $expectedPort = if ($name -eq 'clinical-service') { '18183' } else { '18185' }
            if ($ports.Count -ne 1 -or $ports[0].host_ip -cne '127.0.0.1' -or
                    [string] $ports[0].published -cne $expectedPort -or
                    [int] $ports[0].target -ne 8080 -or $ports[0].protocol -cne 'tcp') {
                throw 'A service has an unexpected host port.'
            }
        } elseif ($ports.Count -ne 0) {
            throw 'An isolated dependency exposes a host port.'
        }
        $mounts = @($service.volumes | Where-Object { $null -ne $_ })
        if ($name -in @('clinical-service', 'analytics-service')) {
            if ($mounts.Count -ne 0) { throw 'An application service has an unexpected mount.' }
        } else {
            $expectedSource = switch ($name) {
                'clinical-postgresql' { 'clinical-postgresql-data' }
                'analytics-mongodb' { 'analytics-mongodb-data' }
                'kafka' { 'kafka-data' }
            }
            if ($mounts.Count -ne 1 -or $mounts[0].type -cne 'volume' -or
                    $mounts[0].source -cne $expectedSource) {
                throw 'An isolated dependency has an unexpected mount.'
            }
        }
    }
    $authorized = $model.volumes.'clinical-postgresql-data'
    if ($null -eq $authorized -or $authorized.external -ne $true -or
            $authorized.name -cne 'neurovision-rehab_clinical-postgresql-data') {
        throw 'The authorized Clinical test volume is not the only expected external volume.'
    }
    foreach ($volume in $model.volumes.PSObject.Properties) {
        if ($volume.Name -ne 'clinical-postgresql-data' -and $volume.Value.external -eq $true) {
            throw 'The Compose model includes another external volume.'
        }
    }
    foreach ($service in $model.services.PSObject.Properties) {
        foreach ($mount in @($service.Value.volumes | Where-Object { $null -ne $_ })) {
            if ($mount.type -cne 'volume' -or
                    ($mount.source -ceq 'clinical-postgresql-data' -and $service.Name -cne 'clinical-postgresql')) {
                throw 'The Compose model includes an unauthorized external mount.'
            }
        }
    }
    if ($model.services.'analytics-service'.environment.CLINICAL_INTERNAL_BASE_URL -cne 'http://clinical-service:8080') {
        throw 'Analytics is not configured to call the real Clinical service.'
    }
    if ($model.services.'analytics-service'.image -cne $nativeImage -or
            $model.services.'analytics-service'.build.dockerfile -cne 'src/main/docker/Dockerfile.native-micro') {
        throw 'Analytics does not resolve to the native image and Dockerfile.'
    }
}

function Invoke-DockerReadOnly([string[]] $arguments) {
    $output = @(& docker @arguments 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'A read-only local Docker inspection failed.' }
    return $output
}

function Assert-LocalNativeImage {
    $identity = @(Invoke-DockerReadOnly @('image', 'inspect', '--format', '{{.Id}}', $nativeImage))
    if ($identity.Count -ne 1 -or $identity[0] -cne $expectedNativeImageId) {
        throw 'The local Analytics native image does not match the verified build.'
    }
}

function Assert-RunningNativeImage {
    $container = @(Invoke-Compose @('ps', '-q', 'analytics-service'))
    if ($container.Count -ne 1 -or $container[0] -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Exactly one isolated Analytics container must be running.'
    }
    $identity = @(Invoke-DockerReadOnly @('inspect', '--format', '{{.Image}}|{{.Config.Image}}|{{.State.Running}}', $container[0]))
    if ($identity.Count -ne 1 -or
            $identity[0] -cne "$expectedNativeImageId|$nativeImage|true") {
        throw 'The running Analytics container is not the verified native image.'
    }
}

function Read-SyntheticFixture {
    $bytes = [IO.File]::ReadAllBytes($fixture)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    if ($actual -cne $fixtureSha256) { throw 'The checked-in synthetic event fixture changed.' }
    try { return [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json }
    catch { throw 'The checked-in synthetic event fixture is invalid.' }
}

function Get-PublicSetting([string] $name) {
    $lines = @(Get-Content -LiteralPath $envFile | Where-Object { $_ -match ('^' + [regex]::Escape($name) + '=') })
    if ($lines.Count -ne 1) { throw 'A required public Cognito setting is missing or duplicated.' }
    $value = $lines[0].Substring($name.Length + 1).Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'A required public Cognito setting is empty.' }
    return $value
}

function ConvertTo-Base64Url([byte[]] $bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64Url([int] $length) {
    $bytes = [byte[]]::new($length)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    ConvertTo-Base64Url $bytes
}

function Get-Claims([string] $token) {
    $parts = $token.Split('.')
    if ($parts.Count -ne 3) { throw 'The access token is malformed.' }
    $encoded = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($encoded.Length % 4) {
        2 { $encoded += '==' }
        3 { $encoded += '=' }
        1 { throw 'The access token is malformed.' }
    }
    try { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json }
    catch { throw 'The access token claims are invalid.' }
}

function Get-PkceAccessToken([hashtable] $settings) {
    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$($settings.Issuer)/.well-known/openid-configuration" -TimeoutSec 10 -MaximumRedirection 0
    } catch { throw 'Cognito discovery failed.' }
    if ($discovery.issuer -cne $settings.Issuer) { throw 'Cognito issuer mismatch.' }
    $authorize = $null
    $tokenEndpoint = $null
    if (-not [uri]::TryCreate([string] $discovery.authorization_endpoint, [UriKind]::Absolute, [ref] $authorize) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint, [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorize.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https' -or
            $authorize.Host -notmatch '\.auth\.us-east-1\.amazoncognito\.com$' -or
            $tokenEndpoint.Host -cne $authorize.Host -or
            $authorize.AbsolutePath -cne '/oauth2/authorize' -or
            $tokenEndpoint.AbsolutePath -cne '/oauth2/token') {
        throw 'Cognito discovery returned unexpected endpoints.'
    }
    $verifier = New-RandomBase64Url 64
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier))) }
    finally { $sha.Dispose() }
    $state = New-RandomBase64Url 32
    $query = [ordered]@{
        client_id = $settings.Client; response_type = 'code'; redirect_uri = $redirect
        scope = 'openid email profile'; state = $state; code_challenge = $challenge
        code_challenge_method = 'S256'; prompt = 'login'
    }
    $encodedQuery = ($query.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString($_.Key), [uri]::EscapeDataString($_.Value)
    }) -join '&'
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add('http://localhost:5173/')
    try {
        $listener.Start()
        Write-Host "PKCE_WAITING: sign in only with the existing $Role test account."
        Start-Process "$($authorize.AbsoluteUri)?$encodedQuery"
        $pending = $listener.GetContextAsync()
        if (-not $pending.Wait([TimeSpan]::FromMinutes(5))) { throw 'The PKCE callback timed out.' }
        $callback = $pending.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -ceq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrEmpty($callback.Request.QueryString['error']) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $message = if ($valid) { 'Authentication complete. Return to the terminal.' } else { 'Authentication rejected.' }
        $bytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentLength64 = $bytes.Length
        $callback.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $callback.Response.Close()
        if (-not $valid) { throw 'The PKCE callback was rejected.' }
        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri -TimeoutSec 15 -MaximumRedirection 0 `
                -ContentType 'application/x-www-form-urlencoded' -Body @{
                    grant_type = 'authorization_code'; client_id = $settings.Client; code = $code
                    redirect_uri = $redirect; code_verifier = $verifier
                }
        } catch { throw 'Cognito token exchange failed.' }
        if ([string]::IsNullOrWhiteSpace($tokens.access_token)) { throw 'Cognito returned no access token.' }
        $claims = Get-Claims $tokens.access_token
        $groups = @($claims.'cognito:groups')
        if ($claims.iss -cne $settings.Issuer -or $claims.client_id -cne $settings.Client -or
                $claims.token_use -cne 'access' -or $groups -cnotcontains $Role -or
                $groups -contains 'ADMIN' -or ($Role -eq 'PATIENT' -and $groups -contains 'DOCTOR') -or
                ($Role -eq 'DOCTOR' -and $groups -contains 'PATIENT') -or
                $null -eq $claims.exp -or [long] $claims.exp -le [DateTimeOffset]::UtcNow.AddMinutes(2).ToUnixTimeSeconds() -or
                [string]::IsNullOrWhiteSpace([string] $claims.sub)) {
            throw 'The access token does not match the expected test role and client.'
        }
        return @{ Token = [string] $tokens.access_token; Subject = [string] $claims.sub }
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}

function Invoke-Compose([string[]] $arguments, [string] $inputText = '') {
    Push-Location $root
    try {
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = 'docker'
        $start.Arguments = (($compose + $arguments) | ForEach-Object {
            '"' + $_.Replace('"', '\"') + '"'
        }) -join ' '
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.CreateNoWindow = $true
        $process = [Diagnostics.Process]::Start($start)
        try {
            $stdoutTask = $process.StandardOutput.ReadToEndAsync()
            $stderrTask = $process.StandardError.ReadToEndAsync()
            if ($inputText) { $process.StandardInput.Write($inputText) }
            $process.StandardInput.Close()
            if (-not $process.WaitForExit(20000)) {
                $process.Kill()
                throw 'An isolated Compose operation timed out.'
            }
            if ($process.ExitCode -ne 0) { throw 'An isolated Compose operation failed.' }
            $null = $stderrTask.Result
            return @($stdoutTask.Result.TrimEnd("`r", "`n") -split "`r?`n")
        } finally { $process.Dispose() }
    } finally { Pop-Location }
}

function Assert-Ready([string] $url) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$url/q/health/ready" -TimeoutSec 5 -MaximumRedirection 0
        if ([int] $response.StatusCode -ne 200) { throw 'Not ready.' }
    } catch { throw 'A required loopback service is not ready.' }
}

function Invoke-LocalGet([string] $url, [string] $token, [int] $timeoutSeconds = 10) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $url `
            -Headers @{ Authorization = "Bearer $token" } -TimeoutSec $timeoutSeconds -MaximumRedirection 0
        return @{ Status = [int] $response.StatusCode; Body = $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        return @{ Status = $status; Body = $null }
    }
}

function ConvertTo-ClinicalSessionId([string[]] $rows) {
    $matches = @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($matches.Count -eq 0) { throw 'No existing Clinical session matches this test identity.' }
    if ($matches.Count -ne 1) { throw 'The Clinical session lookup returned multiple rows.' }
    $session = [guid]::Empty
    if (-not [guid]::TryParse($matches[0].Trim(), [ref] $session) -or $session -eq [guid]::Empty) {
        throw 'The Clinical session lookup did not return one valid session.'
    }
    return $session
}

function Get-MatchingSession([string] $subject) {
    if ($subject -cnotmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'The token subject cannot be safely used for the read-only fixture lookup.' }
    $predicate = if ($Role -eq 'PATIENT') { 'p.patient_cognito_sub' } else { 's.doctor_cognito_sub' }
    $assignment = if ($Role -eq 'DOCTOR') {
        "AND EXISTS (SELECT 1 FROM doctor_patient_assignments a WHERE a.clinical_patient_id=s.clinical_patient_id AND a.doctor_cognito_sub='$subject' AND a.status='ACTIVE')"
    } else { '' }
    $sql = "SELECT s.id FROM therapy_sessions s JOIN clinical_patients p ON p.id=s.clinical_patient_id WHERE $predicate='$subject' $assignment ORDER BY s.started_at DESC LIMIT 1;"
    $rows = @(Invoke-Compose @('exec', '-T', 'clinical-postgresql', 'timeout', '10s', 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-U', 'neurovision_clinical', '-d', 'neurovision_clinical') $sql)
    return ConvertTo-ClinicalSessionId $rows
}

function Publish-OneSyntheticEvent([guid] $session) {
    $event = Read-SyntheticFixture
    $event.eventId = [guid]::NewGuid().ToString()
    $event.batchId = [guid]::NewGuid().ToString()
    $event.therapySessionId = $session.ToString()
    $event.streamId = [guid]::NewGuid().ToString()
    $event.correlationId = [guid]::NewGuid().ToString()
    $now = [DateTimeOffset]::UtcNow
    $event.capturedAt = $now.AddSeconds(-2).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.sentAt = $now.AddSeconds(-1).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.serverReceivedAt = $now.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.sequence = 0
    $key = "$session`:$($event.streamId)"
    $wire = "$key|$($event | ConvertTo-Json -Depth 8 -Compress)"
    $null = Invoke-Compose @('exec', '-T', 'kafka', 'timeout', '15s', '/opt/kafka/bin/kafka-console-producer.sh',
        '--bootstrap-server', 'kafka:9092', '--topic', 'telemetry-batch-accepted.v1',
        '--property', 'parse.key=true', '--property', 'key.separator=|',
        '--producer-property', 'delivery.timeout.ms=10000', '--producer-property', 'request.timeout.ms=5000') "$wire`n"
    return [guid] $event.streamId
}

function Assert-ClinicalRestored {
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        try {
            Assert-Ready 'http://127.0.0.1:18183'
            return
        } catch {
            if ($attempt -eq 12) { throw 'The isolated Clinical service did not become ready after restart.' }
            Start-Sleep -Seconds 2
        }
    }
}

function Assert-FailClosedDuringClinicalOutage([string] $summaryUrl, [string] $token) {
    # Enter the restoration guard before stop: even a failed or timed-out stop can race with restart.
    try {
        $null = Invoke-Compose @('stop', '--timeout', '10', 'clinical-service')
        Assert-Ready 'http://127.0.0.1:18185'
        $unavailable = Invoke-LocalGet $summaryUrl $token
        if ($unavailable.Status -ne 503) { throw 'Analytics did not fail closed while Clinical was stopped.' }
    } finally {
        # Start only the existing Clinical container; never recreate it or touch its volume.
        $null = Invoke-Compose @('start', 'clinical-service')
        Assert-ClinicalRestored
    }
}

try {
    $stage = 'arguments'
    if ($VerifyClinicalUnavailable -and -not $RunPkce) {
        throw 'The Clinical-unavailable probe requires interactive PKCE authorization.'
    }
    $stage = 'local preflight'
    $settings = Assert-LocalSetup
    if (-not $RunPkce -and -not $CheckNativeRuntime) { return }
    $stage = 'runtime readiness'
    Assert-Ready 'http://127.0.0.1:18183'
    Assert-Ready 'http://127.0.0.1:18185'
    Assert-RunningNativeImage
    if (-not $RunPkce) {
        Write-Host 'CA13C_NATIVE_RUNTIME_OK: both local services ready; running Analytics image identity verified; harness did not run PKCE or publish an event.'
        return
    }
    $stage = 'PKCE login'
    $identity = Get-PkceAccessToken $settings
    $stage = 'Clinical session lookup'
    $session = Get-MatchingSession $identity.Subject
    $stage = 'Clinical authorization'
    $clinicalUrl = "http://127.0.0.1:18183/api/internal/v1/therapy-sessions/$session/telemetry-read-authorization"
    $clinical = Invoke-LocalGet $clinicalUrl $identity.Token
    if ($clinical.Status -ne 200) { throw "Clinical did not authorize the matching session (HTTP $($clinical.Status))." }
    $stage = 'synthetic publish'
    $stream = Publish-OneSyntheticEvent $session
    $stage = 'Analytics projection query'
    $summaryUrl = "http://127.0.0.1:18185/api/v1/analytics/sessions/$session/streams/$stream/summary"
    $verified = $false
    $projectionWait = [System.Diagnostics.Stopwatch]::StartNew()
    while ($projectionWait.ElapsedMilliseconds -lt 20000) {
        $remainingMs = 20000 - $projectionWait.ElapsedMilliseconds
        if ($remainingMs -lt 1000) { break }
        $requestTimeoutSeconds = [int] [Math]::Min(10, [Math]::Floor($remainingMs / 1000))
        $summary = Invoke-LocalGet $summaryUrl $identity.Token $requestTimeoutSeconds
        if ($summary.Status -eq 200) {
            try { $body = ConvertFrom-Json -InputObject $summary.Body } catch { throw 'Analytics returned invalid JSON.' }
            if ($body.schemaVersion -ne 1 -or $body.sourceSchemaVersion -ne 1 -or
                    $body.calculationVersion -cne 'baseline-v1' -or [long] $body.revision -lt 1 -or
                    $body.therapySessionId -cne $session.ToString() -or
                    $body.streamId -cne $stream.ToString()) {
                throw 'Analytics projection metadata did not match the synthetic event.'
            }
            $verified = $true
            break
        }
        if ($summary.Status -eq 0 -and $projectionWait.ElapsedMilliseconds -ge 20000) { break }
        if ($summary.Status -ne 404) { throw "Analytics returned unexpected HTTP status $($summary.Status)." }
        $remainingMs = 20000 - $projectionWait.ElapsedMilliseconds
        if ($remainingMs -gt 0) { Start-Sleep -Milliseconds ([int] [Math]::Min(2000, $remainingMs)) }
    }
    if (-not $verified) { throw 'Analytics projection was not available within the bounded wait.' }
    $stage = 'unrelated-session denial'
    $unrelated = [guid]::NewGuid()
    $denied = Invoke-LocalGet "http://127.0.0.1:18185/api/v1/analytics/sessions/$unrelated/streams/$stream/summary" $identity.Token
    if ($denied.Status -ne 403) { throw "Unrelated session returned unexpected HTTP status $($denied.Status)." }
    if ($VerifyClinicalUnavailable) {
        $stage = 'Clinical-outage probe'
        Assert-FailClosedDuringClinicalOutage $summaryUrl $identity.Token
        Write-Host "CA13C_FAIL_CLOSED_VERIFIED: role=$Role; Analytics=503; Clinical=restored."
    }
    Write-Host "CA13C_VERIFIED: role=$Role; Clinical=200; Analytics=200; revision=present; unrelated=403; one synthetic V1 event."
} catch {
    # Never surface exception detail: HTTP and CLI errors can contain URLs, subjects, or tokens.
    $location = if ($stage -eq 'local preflight') { "$stage/$script:substage" } else { $stage }
    $safeDetail = if ($stage -in @('Clinical session lookup', 'Clinical authorization')) { " $($_.Exception.Message)" } else { '' }
    Write-Error -Message "CA13C smoke stopped safely at $location.$safeDetail Inspect the isolated local setup without printing credentials or payloads." -ErrorAction Continue
    exit 1
}
