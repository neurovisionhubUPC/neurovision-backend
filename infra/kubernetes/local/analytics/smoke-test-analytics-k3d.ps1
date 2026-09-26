[CmdletBinding()]
param(
    [switch] $PreflightOnly,
    [switch] $AuthorizedSyntheticRun,
    [ValidateSet('neurovision', 'neurovision-analytics-smoke')][string] $Namespace = 'neurovision'
)

$ErrorActionPreference = 'Stop'
$script:stage = 'arguments'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$envFile = Join-Path $root '.env'
$fixturePath = Join-Path $root 'analytics-service/src/test/resources/fixtures/telemetry/v1/accepted-event-wire.json'
$fixtureSha256 = 'ed58c80f41d831f7c4926bd83bbb5469f0cefc4b035276ccd242b150eece7779'
$issuerHost = 'cognito-idp.us-east-1.amazonaws.com'
$redirectUri = 'http://localhost:5173/auth/callback'
$clinicalOrigin = 'http://127.0.0.1:18083'
$analyticsOrigin = 'http://127.0.0.1:18085'
$forwards = @()

function Invoke-Kubectl([string[]] $Arguments) {
    $result = @(& kubectl @Arguments 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'A local Kubernetes inspection failed.' }
    return $result
}

function Assert-Context {
    $context = @(Invoke-Kubectl @('config', 'current-context'))
    if ($context.Count -ne 1 -or $context[0] -cne 'k3d-neurovision-local') {
        throw 'The active Kubernetes context is not the approved local cluster.'
    }
}

function Assert-Workloads {
    foreach ($name in @('clinical-service', 'analytics-service')) {
        $workloadNamespace = if ($name -eq 'clinical-service') { 'neurovision' } else { $Namespace }
        $json = (Invoke-Kubectl @('get', 'deployment', $name, '-n', $workloadNamespace, '-o', 'json')) -join "`n"
        $deployment = ConvertFrom-Json -InputObject $json
        if ($deployment.spec.replicas -ne 1 -or $deployment.status.readyReplicas -ne 1 -or
                $deployment.status.availableReplicas -ne 1) {
            throw 'A required application workload is not ready.'
        }
    }
    foreach ($name in @('analytics-mongodb', 'kafka')) {
        $json = (Invoke-Kubectl @('get', 'statefulset', $name, '-n', $Namespace, '-o', 'json')) -join "`n"
        $statefulSet = ConvertFrom-Json -InputObject $json
        if ($statefulSet.spec.replicas -ne 1 -or $statefulSet.status.readyReplicas -ne 1) {
            throw 'A required data workload is not ready.'
        }
    }
    $secret = @(Invoke-Kubectl @('get', 'secret', 'analytics-gaze-plot-cursor-signing-key',
        '-n', $Namespace, '-o', 'name'))
    if ($secret.Count -ne 1 -or $secret[0] -cne 'secret/analytics-gaze-plot-cursor-signing-key') {
        throw 'The Analytics cursor-signing Secret is missing.'
    }
}

function Get-DbRows([string] $Sql) {
    $rows = @(Invoke-Kubectl @('-n', 'neurovision', 'exec', 'clinical-postgresql-0', '--', 'sh', '-c',
        'psql -X -q -A -t -F "|" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
        'sh', $Sql))
    return @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-SoleClinicalFixture {
    $countsSql = @'
SELECT (SELECT count(*) FROM therapy_sessions),
       (SELECT count(*) FROM therapy_sessions WHERE status = 'ACTIVE'),
       (SELECT count(*) FROM therapy_plans),
       (SELECT count(*) FROM doctor_patient_assignments WHERE status = 'ACTIVE');
'@
    $counts = @(Get-DbRows $countsSql)
    if ($counts.Count -ne 1 -or $counts[0] -cne '1|1|1|1') {
        throw 'The sole active Clinical fixture does not match the expected synthetic baseline.'
    }
    $identitySql = @'
SELECT s.id, p.patient_cognito_sub, a.doctor_cognito_sub
FROM therapy_sessions s
JOIN clinical_patients p ON p.id = s.clinical_patient_id
JOIN doctor_patient_assignments a ON a.clinical_patient_id = s.clinical_patient_id
WHERE s.status = 'ACTIVE' AND a.status = 'ACTIVE';
'@
    $rows = @(Get-DbRows $identitySql)
    if ($rows.Count -ne 1) { throw 'The active Clinical fixture is ambiguous.' }
    $fields = $rows[0].Split('|')
    $session = [guid]::Empty
    if ($fields.Count -ne 3 -or -not [guid]::TryParse($fields[0], [ref] $session) -or
            $session -eq [guid]::Empty -or
            $fields[1] -cnotmatch '^[A-Za-z0-9_-]{1,128}$' -or
            $fields[2] -cnotmatch '^[A-Za-z0-9_-]{1,128}$' -or
            $fields[1] -ceq $fields[2]) {
        throw 'The active Clinical fixture identity is invalid.'
    }
    return @{ Session = $session; PatientSubject = $fields[1]; DoctorSubject = $fields[2] }
}

function Get-MongoCounts {
    $expression = 'var d=db.getSiblingDB("neurovision_analytics");JSON.stringify({inbox:d.getCollection("telemetry_inbox_v1").countDocuments({}),summaries:d.getCollection("stream_summaries_v1").countDocuments({})})'
    $rows = @(Invoke-Kubectl @('-n', $Namespace, 'exec', 'analytics-mongodb-0', '--',
        'mongosh', '--quiet', '--eval', $expression))
    if ($rows.Count -ne 1) { throw 'MongoDB aggregate counts are unavailable.' }
    try { $counts = ConvertFrom-Json -InputObject $rows[0] }
    catch { throw 'MongoDB aggregate counts are malformed.' }
    if ($null -eq $counts.inbox -or $null -eq $counts.summaries -or
            [long] $counts.inbox -lt 0 -or [long] $counts.summaries -lt 0) {
        throw 'MongoDB aggregate counts are malformed.'
    }
    return $counts
}

function Get-TopicOffsets([string] $Topic, [int] $Time) {
    if ($Topic -cnotin @('telemetry-batch-accepted.v1', 'analytics-telemetry.v1.dlq') -or
            $Time -notin @(-1, -2)) { throw 'Unexpected Kafka offset request.' }
    $rows = @(Invoke-Kubectl @('-n', $Namespace, 'exec', 'kafka-0', '-c', 'kafka', '--',
        '/opt/kafka/bin/kafka-get-offsets.sh', '--bootstrap-server', 'kafka:9092',
        '--topic', $Topic, '--time', [string] $Time))
    if ($rows.Count -ne 3) { throw 'Kafka topic partition count is unexpected.' }
    $offsets = @{}
    foreach ($row in $rows) {
        $fields = $row.Split(':')
        if ($fields.Count -ne 3 -or $fields[0] -cne $Topic -or
                $fields[1] -notmatch '^[0-2]$' -or $fields[2] -notmatch '^\d+$') {
            throw 'Kafka offset response is malformed.'
        }
        if ($offsets.ContainsKey($fields[1])) { throw 'Duplicate Kafka partition.' }
        $offsets[$fields[1]] = [long] $fields[2]
    }
    if ($offsets.Count -ne 3) { throw 'Kafka partition coverage is incomplete.' }
    return $offsets
}

function Get-ExpectedSourceTotal {
    if ($Namespace -eq 'neurovision-analytics-smoke') { return 0L }
    if ($Namespace -eq 'neurovision') { return 1L }
    throw 'Unexpected smoke namespace.'
}

function Assert-EmptyPipeline {
    $mongo = Get-MongoCounts
    $sourceEarliest = Get-TopicOffsets 'telemetry-batch-accepted.v1' -2
    $sourceLatest = Get-TopicOffsets 'telemetry-batch-accepted.v1' -1
    $dlqLatest = Get-TopicOffsets 'analytics-telemetry.v1.dlq' -1
    $sourceTotal = 0L
    $dlqTotal = 0L
    foreach ($partition in @('0', '1', '2')) {
        if ($sourceEarliest[$partition] -ne $sourceLatest[$partition]) {
            throw 'The source Kafka topic contains unconsumed historical records.'
        }
        $sourceTotal += $sourceLatest[$partition]
        $dlqTotal += $dlqLatest[$partition]
    }
    $expectedSourceTotal = Get-ExpectedSourceTotal
    if ([long] $mongo.inbox -ne 0 -or [long] $mongo.summaries -ne 0 -or
            $sourceTotal -ne $expectedSourceTotal -or $dlqTotal -ne 0) {
        throw 'The synthetic pipeline baseline differs from the approved empty projection state.'
    }
    return $sourceLatest
}

function Assert-PortFree([int] $Port) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $probe.Start() } catch { throw 'A required loopback port is occupied.' }
    finally { $probe.Stop() }
}

function Start-Forward([string] $Service, [int] $Port, [string] $ForwardNamespace) {
    Assert-PortFree $Port
    $process = Start-Process -FilePath 'kubectl' -ArgumentList @('-n', $ForwardNamespace, 'port-forward',
        "service/$Service", "${Port}:8080", '--address', '127.0.0.1') -PassThru -WindowStyle Hidden
    if ($null -eq $process) { throw 'A loopback port-forward could not start.' }
    $script:forwards += $process
}

function Invoke-LocalGet([string] $Url, [string] $Token = '', [int] $TimeoutSeconds = 5) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Url -Headers $headers `
            -TimeoutSec $TimeoutSeconds -MaximumRedirection 0
        return @{ Status = [int] $response.StatusCode; Body = [string] $response.Content }
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int] $_.Exception.Response.StatusCode }
        return @{ Status = $status; Body = $null }
    }
}

function Assert-Status([hashtable] $Response, [int] $Expected) {
    if ($Response.Status -ne $Expected) { throw 'A local HTTP status differed from the expected authorization result.' }
}

function Assert-LocalReady([string] $Origin) {
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $result = Invoke-LocalGet "$Origin/q/health/ready"
        if ($result.Status -eq 200) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'A loopback port-forward did not become ready.'
}

function Get-PublicSetting([string] $Name) {
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) { throw 'Local public Cognito settings are missing.' }
    $matches = @(Get-Content -LiteralPath $envFile | Where-Object {
        $_ -match ('^' + [regex]::Escape($Name) + '=')
    })
    if ($matches.Count -ne 1) { throw 'A public Cognito setting is ambiguous.' }
    $value = $matches[0].Substring($Name.Length + 1).Trim().Trim('"')
    if (-not $value) { throw 'A public Cognito setting is empty.' }
    return $value
}

function Get-OidcSettings {
    $pool = Get-PublicSetting 'COGNITO_USER_POOL_ID'
    $client = Get-PublicSetting 'COGNITO_CLIENT_ID'
    $issuer = (Get-PublicSetting 'COGNITO_ISSUER_URL').TrimEnd('/')
    if ($pool -cne 'us-east-1_6eG3zLkZ2' -or
            $issuer -cne "https://$issuerHost/$pool" -or
            $client -cne '52rlcllcsdk0rdvbqebe8efbpm') {
        throw 'The public development Cognito configuration is unexpected.'
    }
    $config = (Invoke-Kubectl @('get', 'configmap', 'analytics-service-config', '-n',
        $Namespace, '-o', 'json')) -join "`n"
    $data = (ConvertFrom-Json -InputObject $config).data
    $clinicalUrl = if ($Namespace -eq 'neurovision-analytics-smoke') {
        'http://clinical-service.neurovision.svc.cluster.local:8080'
    } else { 'http://clinical-service:8080' }
    if ($data.COGNITO_CLIENT_ID -cne $client -or $data.COGNITO_ISSUER_URL -cne $issuer -or
            $data.CLINICAL_INTERNAL_BASE_URL -cne $clinicalUrl -or
            $data.KAFKA_BOOTSTRAP_SERVERS -cne 'kafka:9092' -or
            $data.ANALYTICS_MONGODB_CONNECTION_STRING -cne 'mongodb://analytics-mongodb:27017/neurovision_analytics') {
        throw 'Kubernetes Analytics does not use the expected development issuer.'
    }
    return @{ Issuer = $issuer; Client = $client }
}

function ConvertTo-Base64Url([byte[]] $Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-RandomBase64Url([int] $ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ConvertTo-Base64Url $bytes
}

function Get-TokenClaims([string] $Token) {
    $parts = $Token.Split('.')
    if ($parts.Count -ne 3) { throw 'The access token is malformed.' }
    $encoded = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($encoded.Length % 4) {
        2 { $encoded += '==' }
        3 { $encoded += '=' }
        1 { throw 'The access token is malformed.' }
    }
    try { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)) | ConvertFrom-Json }
    catch { throw 'The access token claims are unreadable.' }
}

function Get-RoleToken([string] $Role, [hashtable] $Settings) {
    try {
        $discovery = Invoke-RestMethod -Method Get -Uri "$($Settings.Issuer)/.well-known/openid-configuration" `
            -TimeoutSec 10 -MaximumRedirection 0
    } catch { throw 'Development Cognito discovery failed.' }
    $authorization = $null
    $tokenEndpoint = $null
    if ($discovery.issuer -cne $Settings.Issuer -or
            -not [uri]::TryCreate([string] $discovery.authorization_endpoint, [UriKind]::Absolute, [ref] $authorization) -or
            -not [uri]::TryCreate([string] $discovery.token_endpoint, [UriKind]::Absolute, [ref] $tokenEndpoint) -or
            $authorization.Scheme -ne 'https' -or $tokenEndpoint.Scheme -ne 'https' -or
            $authorization.Host -notmatch '\.auth\.us-east-1\.amazoncognito\.com$' -or
            $authorization.Host -cne $tokenEndpoint.Host -or
            $authorization.AbsolutePath -cne '/oauth2/authorize' -or
            $tokenEndpoint.AbsolutePath -cne '/oauth2/token') {
        throw 'Development Cognito discovery returned unexpected endpoints.'
    }
    $verifier = New-RandomBase64Url 64
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier))) }
    finally { $sha.Dispose() }
    $state = New-RandomBase64Url 32
    $query = [ordered]@{
        client_id = $Settings.Client; response_type = 'code'; redirect_uri = $redirectUri
        scope = 'openid email profile'; state = $state; code_challenge = $challenge
        code_challenge_method = 'S256'; prompt = 'login'
    }
    $queryString = ($query.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString($_.Key), [uri]::EscapeDataString($_.Value)
    }) -join '&'
    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add('http://localhost:5173/')
    try {
        $listener.Start()
        Write-Host "ROLE ${Role}: sign in with the existing $Role test account now."
        Start-Process "$($authorization.AbsoluteUri)?$queryString"
        $pending = $listener.GetContextAsync()
        if (-not $pending.Wait([TimeSpan]::FromMinutes(5))) { throw 'The PKCE callback timed out.' }
        $callback = $pending.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -ceq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrWhiteSpace($callback.Request.QueryString['error']) -and
            -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $message = if ($valid) { 'Authentication complete. Return to the terminal.' } else { 'Authentication rejected.' }
        $bytes = [Text.Encoding]::UTF8.GetBytes($message)
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $callback.Response.ContentLength64 = $bytes.Length
        $callback.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $callback.Response.Close()
        if (-not $valid) { throw 'The PKCE callback was rejected.' }
        try {
            $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri `
                -TimeoutSec 15 -MaximumRedirection 0 -ContentType 'application/x-www-form-urlencoded' `
                -Body @{ grant_type = 'authorization_code'; client_id = $Settings.Client;
                    code = $code; redirect_uri = $redirectUri; code_verifier = $verifier }
        } catch { throw 'The development Cognito token exchange failed.' }
        if (-not $tokens.access_token) { throw 'Cognito returned no access token.' }
        $claims = Get-TokenClaims $tokens.access_token
        $groups = @($claims.'cognito:groups')
        if ($claims.iss -cne $Settings.Issuer -or $claims.client_id -cne $Settings.Client -or
                $claims.token_use -cne 'access' -or $groups -cnotcontains $Role -or
                $groups -contains 'ADMIN' -or
                ($Role -eq 'PATIENT' -and $groups -contains 'DOCTOR') -or
                ($Role -eq 'DOCTOR' -and $groups -contains 'PATIENT') -or
                -not $claims.sub -or $null -eq $claims.exp -or
                [long] $claims.exp -le [DateTimeOffset]::UtcNow.AddMinutes(2).ToUnixTimeSeconds()) {
            throw 'The access token does not match the expected role or client.'
        }
        return @{ Token = [string] $tokens.access_token; Subject = [string] $claims.sub }
    } finally {
        if ($listener.IsListening) { $listener.Stop() }
        $listener.Close()
    }
}

function Read-SyntheticFixture {
    $bytes = [IO.File]::ReadAllBytes($fixturePath)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    if ($actual -cne $fixtureSha256) { throw 'The checked-in synthetic event fixture changed.' }
    try { $event = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json }
    catch { throw 'The synthetic event fixture is malformed.' }
    if ($event.schemaVersion -ne 1 -or @($event.gazeSamples).Count -ne 1 -or
            @($event.gameplaySamples).Count -ne 1) {
        throw 'The synthetic event shape is unexpected.'
    }
    return $event
}

function Publish-OneSyntheticEvent([guid] $Session) {
    $event = Read-SyntheticFixture
    $event.eventId = [guid]::NewGuid().ToString()
    $event.batchId = [guid]::NewGuid().ToString()
    $event.therapySessionId = $Session.ToString()
    $event.streamId = [guid]::NewGuid().ToString()
    $event.correlationId = [guid]::NewGuid().ToString()
    $now = [DateTimeOffset]::UtcNow
    $event.capturedAt = $now.AddSeconds(-2).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.sentAt = $now.AddSeconds(-1).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.serverReceivedAt = $now.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $event.sequence = 0
    $wire = "$Session`:$($event.streamId)|$($event | ConvertTo-Json -Depth 8 -Compress)"
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = 'kubectl'
    $start.Arguments = "-n $Namespace exec -i kafka-0 -c kafka -- /opt/kafka/bin/kafka-console-producer.sh " +
        '--bootstrap-server kafka:9092 --topic telemetry-batch-accepted.v1 ' +
        '--property parse.key=true --property key.separator=| ' +
        '--producer-property delivery.timeout.ms=10000 --producer-property request.timeout.ms=5000'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($wire)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(20000)) {
            $process.Kill()
            throw 'The one-shot synthetic Kafka publication timed out; do not retry automatically.'
        }
        $null = $stdout.Result
        $null = $stderr.Result
        if ($process.ExitCode -ne 0) {
            throw 'The one-shot synthetic Kafka publication failed; inspect offsets before any retry.'
        }
    } finally { $process.Dispose() }
    return @{ Stream = [guid] $event.streamId; Event = $event }
}

function Assert-Projection([guid] $Session, [guid] $Stream, [string] $Token) {
    $url = "$analyticsOrigin/api/v1/analytics/sessions/$Session/streams/$Stream/summary"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.ElapsedMilliseconds -lt 20000) {
        $remaining = 20000 - $timer.ElapsedMilliseconds
        if ($remaining -lt 1000) { break }
        $timeout = [int] [Math]::Min(5, [Math]::Floor($remaining / 1000))
        $response = Invoke-LocalGet $url $Token $timeout
        if ($response.Status -eq 200) {
            try { $body = ConvertFrom-Json -InputObject $response.Body }
            catch { throw 'Analytics returned malformed summary JSON.' }
            if ($body.schemaVersion -ne 1 -or $body.sourceSchemaVersion -ne 1 -or
                    $body.calculationVersion -cne 'baseline-v1' -or
                    $body.therapySessionId -cne $Session.ToString() -or
                    $body.streamId -cne $Stream.ToString() -or [long] $body.revision -lt 1) {
                throw 'Analytics returned a summary for a different synthetic event.'
            }
            return
        }
        if ($response.Status -ne 404) { throw 'Analytics returned an unexpected status while awaiting projection.' }
        $remaining = 20000 - $timer.ElapsedMilliseconds
        if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int] [Math]::Min(1000, $remaining)) }
    }
    throw 'Analytics did not expose the synthetic summary within 20 seconds.'
}

function Get-EpochMillis($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value.ToUnixTimeMilliseconds() }
    if ($Value -is [DateTime]) { return ([DateTimeOffset] $Value.ToUniversalTime()).ToUnixTimeMilliseconds() }
    if ($Value -is [string]) {
        return [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture).ToUnixTimeMilliseconds()
    }
    throw 'An observed sample timestamp is missing.'
}

function Assert-ViewContent([string] $Kind, [hashtable] $Response,
        [guid] $Session, [guid] $Stream, $Event) {
    Assert-Status $Response 200
    try { $body = ConvertFrom-Json -InputObject $Response.Body }
    catch { throw 'Analytics returned malformed projection JSON.' }
    if ($null -eq $body -or $body.contractVersion -ne 1) {
        throw 'Analytics projection contract version is unexpected.'
    }
    switch ($Kind) {
        'heatmap' {
            $expectedCell = [int] ([Math]::Floor([double] $Event.gazeSamples[0].y * 10) * 10 +
                [Math]::Floor([double] $Event.gazeSamples[0].x * 10))
            $cells = @($body.cellCounts)
            if ($body.sourceSchemaVersion -ne 1 -or
                    $body.therapySessionId -cne $Session.ToString() -or
                    $body.streamId -cne $Stream.ToString() -or
                    $body.calculationVersion -cne 'baseline-v1' -or
                    [long] $body.revision -lt 1 -or $body.rows -ne 10 -or $body.columns -ne 10 -or
                    $body.coordinateSpace -cne 'normalized-0..1' -or
                    $body.cellWeight -cne 'raw-gaze-sample-count' -or
                    $cells.Count -ne 100 -or [long] $body.gazeSampleCount -ne 1 -or
                    [long] $cells[$expectedCell] -ne 1 -or
                    [long] ($cells | Measure-Object -Sum).Sum -ne 1 -or
                    [long] $body.minimumSequence -ne 0 -or [long] $body.maximumSequence -ne 0 -or
                    @($body.missingSequenceRanges).Count -ne 0) {
                throw 'Heatmap does not describe the single synthetic gaze sample.'
            }
        }
        'gaze-plot' {
            $points = @($body.points)
            if ($body.pointSemantics -cne 'OBSERVED_GAZE_SAMPLES' -or
                    $body.calculationVersion -cne 'baseline-v1' -or
                    [long] $body.projectionRevision -lt 1 -or
                    $body.completeness.sourceSchemaVersion -ne 1 -or
                    [long] $body.completeness.observedGazePointCount -ne 1 -or
                    $points.Count -ne 1 -or $body.nextCursor -or
                    [long] $points[0].sequence -ne 0 -or
                    [int] $points[0].gazeSampleIndex -ne 0 -or
                    (Get-EpochMillis $points[0].effectiveTime) -ne (Get-EpochMillis $Event.capturedAt) -or
                    [double] $points[0].x -ne [double] $Event.gazeSamples[0].x -or
                    [double] $points[0].y -ne [double] $Event.gazeSamples[0].y -or
                    [double] $points[0].confidence -ne [double] $Event.gazeSamples[0].confidence) {
                throw 'Gaze plot does not describe the single synthetic gaze sample.'
            }
        }
        'replay' {
            $gaze = @($body.gazeSamples.samples)
            $gameplay = @($body.gameplaySamples.samples)
            if ($body.source -cne 'admitted-telemetry-kafka-v1' -or
                    $body.therapySessionId -cne $Session.ToString() -or
                    $body.streamId -cne $Stream.ToString() -or
                    [long] $body.projectionRevision -lt 1 -or
                    $body.sourceSchemaVersion -ne 1 -or
                    $gaze.Count -ne 1 -or $gameplay.Count -ne 1 -or
                    $body.gazeSamples.nextCursor -or $body.gameplaySamples.nextCursor -or
                    $gaze[0].batchId -cne $Event.batchId -or
                    $gameplay[0].batchId -cne $Event.batchId -or
                    [long] $gaze[0].sequence -ne 0 -or [long] $gameplay[0].sequence -ne 0 -or
                    [int] $gaze[0].sourceArrayIndex -ne 0 -or
                    [int] $gameplay[0].sourceArrayIndex -ne 0 -or
                    [long] $gaze[0].offsetMs -ne [long] $Event.gazeSamples[0].offsetMs -or
                    [long] $gameplay[0].offsetMs -ne [long] $Event.gameplaySamples[0].offsetMs -or
                    (Get-EpochMillis $gaze[0].capturedAt) -ne (Get-EpochMillis $Event.capturedAt) -or
                    (Get-EpochMillis $gameplay[0].capturedAt) -ne (Get-EpochMillis $Event.capturedAt) -or
                    [double] $gaze[0].x -ne [double] $Event.gazeSamples[0].x -or
                    [double] $gaze[0].y -ne [double] $Event.gazeSamples[0].y -or
                    [double] $gaze[0].confidence -ne [double] $Event.gazeSamples[0].confidence -or
                    [double] $gameplay[0].playerX -ne [double] $Event.gameplaySamples[0].playerX -or
                    [double] $gameplay[0].playerY -ne [double] $Event.gameplaySamples[0].playerY -or
                    [double] $gameplay[0].targetX -ne [double] $Event.gameplaySamples[0].targetX -or
                    [double] $gameplay[0].targetY -ne [double] $Event.gameplaySamples[0].targetY -or
                    [long] $gameplay[0].score -ne [long] $Event.gameplaySamples[0].score -or
                    [double] $gameplay[0].speed -ne [double] $Event.gameplaySamples[0].speed -or
                    [double] $gameplay[0].progress -ne [double] $Event.gameplaySamples[0].progress) {
                throw 'Replay does not contain the observed synthetic gaze and gameplay pair.'
            }
        }
        default { throw 'Unexpected Analytics projection kind.' }
    }
}

function Assert-Views([guid] $Session, [guid] $Stream, [string] $Token, $Event) {
    foreach ($kind in @('heatmap', 'gaze-plot', 'replay')) {
        $url = "$analyticsOrigin/api/v1/analytics/sessions/$Session/streams/$Stream/$kind"
        Assert-ViewContent $kind (Invoke-LocalGet $url $Token) $Session $Stream $Event
    }
}

if ($MyInvocation.InvocationName -ne '.') {
try {
    if ([bool] $PreflightOnly -eq [bool] $AuthorizedSyntheticRun) {
        throw 'Select exactly one mode: -PreflightOnly or -AuthorizedSyntheticRun.'
    }
    $script:stage = 'Kubernetes context'
    Assert-Context
    $script:stage = 'workload readiness'
    Assert-Workloads
    $script:stage = 'Clinical fixture'
    $fixture = Get-SoleClinicalFixture
    $script:stage = 'synthetic fixture'
    $null = Read-SyntheticFixture
    $script:stage = 'public identity configuration'
    $settings = Get-OidcSettings
    $script:stage = 'loopback port-forwards'
    Start-Forward 'clinical-service' 18083 'neurovision'
    Start-Forward 'analytics-service' 18085 $Namespace
    Assert-LocalReady $clinicalOrigin
    Assert-LocalReady $analyticsOrigin
    $script:stage = 'anonymous denial'
    $anonymous = Invoke-LocalGet "$analyticsOrigin/api/v1/analytics/sessions/$([guid]::NewGuid())/streams/$([guid]::NewGuid())/summary"
    Assert-Status $anonymous 401
    if ($PreflightOnly) {
        $script:stage = 'empty pipeline'
        $null = Assert-EmptyPipeline
        Write-Host "K3D_ANALYTICS_PREFLIGHT_OK: namespace=$Namespace; workloads=ready; fixture=sole-active; source=empty-retained; inbox=0; summary=0; dlq=0; anonymous=401; no login or event."
        return
    }

    $script:stage = 'patient identity'
    $patient = Get-RoleToken 'PATIENT' $settings
    if ($patient.Subject -cne $fixture.PatientSubject) {
        throw 'The PATIENT account does not own the sole active Clinical session.'
    }
    $clinicalUrl = "$clinicalOrigin/api/internal/v1/therapy-sessions/$($fixture.Session)/telemetry-read-authorization"
    Assert-Status (Invoke-LocalGet $clinicalUrl $patient.Token) 200

    $script:stage = 'doctor identity'
    $doctor = Get-RoleToken 'DOCTOR' $settings
    if ($doctor.Subject -cne $fixture.DoctorSubject -or $doctor.Subject -ceq $patient.Subject) {
        throw 'The DOCTOR account does not own the sole active Clinical assignment.'
    }
    Assert-Status (Invoke-LocalGet $clinicalUrl $doctor.Token) 200

    $script:stage = 'prepublication empty pipeline'
    $baseline = Assert-EmptyPipeline

    $script:stage = 'one synthetic event'
    $published = Publish-OneSyntheticEvent $fixture.Session
    $stream = $published.Stream
    $script:stage = 'patient projections'
    Assert-Projection $fixture.Session $stream $patient.Token
    Assert-Views $fixture.Session $stream $patient.Token $published.Event
    Assert-Status (Invoke-LocalGet "$analyticsOrigin/api/v1/analytics/sessions/$([guid]::NewGuid())/streams/$stream/summary" $patient.Token) 403
    Assert-Status (Invoke-LocalGet "$analyticsOrigin/api/v1/analytics/sessions/$($fixture.Session)/streams/$stream/summary") 401
    $mongo = Get-MongoCounts
    if ([long] $mongo.inbox -ne 1 -or [long] $mongo.summaries -ne 1) {
        throw 'The MongoDB projection counts differ from the one-event expectation.'
    }
    $after = Get-TopicOffsets 'telemetry-batch-accepted.v1' -1
    $increase = 0L
    foreach ($partition in @('0', '1', '2')) { $increase += $after[$partition] - $baseline[$partition] }
    if ($increase -ne 1 -or ((Get-TopicOffsets 'analytics-telemetry.v1.dlq' -1).Values | Measure-Object -Sum).Sum -ne 0) {
        throw 'Kafka offsets do not match one accepted event and an empty Analytics DLQ.'
    }
    Write-Host 'K3D_ANALYTICS_PATIENT_OK: Clinical=200; four Analytics queries=200; unrelated=403; anonymous=401; one synthetic event; inbox=1; summary=1; dlq=0.'

    $script:stage = 'doctor projections'
    Assert-Projection $fixture.Session $stream $doctor.Token
    Assert-Views $fixture.Session $stream $doctor.Token $published.Event
    Write-Host 'K3D_ANALYTICS_DOCTOR_OK: same session and stream; Clinical=200; four Analytics queries=200; no second event.'
} catch {
    # Never print exception details: they may contain tokens, URLs, session IDs, or event payloads.
    Write-Error -Message "K3D Analytics smoke stopped safely at $script:stage. Inspect local state without printing identifiers or credentials." -ErrorAction Continue
    exit 1
} finally {
    foreach ($process in $forwards) {
        if ($null -ne $process) {
            try { if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null } }
            catch { }
            $process.Dispose()
        }
    }
}
}
