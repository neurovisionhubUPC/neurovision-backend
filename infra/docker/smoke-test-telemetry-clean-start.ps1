[CmdletBinding()]
param(
    [ValidateSet('OfflineTest', 'Preflight', 'AuthorizedStart')]
    [string] $Mode = 'OfflineTest',
    [switch] $AuthorizeCleanStart,
    [switch] $ConfirmDisposableSyntheticEnvironment
)

$ErrorActionPreference = 'Stop'
$script:projectName = 'telemetry-clean-start-20260926'
$script:networkName = 'telemetry-clean-start-20260926-network'
$script:volumeNames = @(
    'telemetry-clean-start-20260926-iam-data',
    'telemetry-clean-start-20260926-clinical-data',
    'telemetry-clean-start-20260926-telemetry-data',
    'telemetry-clean-start-20260926-kafka-data'
)
$script:ports = @(18081, 18083, 18084, 19092)
$script:expectedServices = @(
    'iam-postgresql', 'iam-service', 'clinical-postgresql',
    'telemetry-timescaledb', 'kafka', 'kafka-init',
    'clinical-service', 'telemetry-service'
)
$script:repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:composeFile = Join-Path $PSScriptRoot 'telemetry-clean-start.compose.yaml'
$script:envFile = Join-Path $script:repositoryRoot '.env'
$script:stage = 'mode_guard'
$script:collisionSubguard = 'not_started'

function Assert-RequiredOidcConfiguration([string[]] $Lines) {
    $values = @{}
    foreach ($key in @('COGNITO_CLIENT_ID', 'COGNITO_ISSUER_URL')) {
        $matches = @($Lines | Where-Object { $_ -match ('^\s*{0}\s*=' -f [regex]::Escape($key)) })
        if ($matches.Count -ne 1 -or $matches[0] -notmatch ('^\s*{0}\s*=\s*(.*?)\s*$' -f [regex]::Escape($key))) {
            throw 'Required development OIDC configuration is missing or invalid in repository-root .env.'
        }
        $value = $Matches[1].Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or
                ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrWhiteSpace($value) -or
                $value -match '(?i)(placeholder|example|changeme|change-me|issuer\.invalid|<[^>]+>)') {
            throw 'Required development OIDC configuration is missing or invalid in repository-root .env.'
        }
        $values[$key] = $value
    }
    $issuerUri = $null
    if (-not [Uri]::TryCreate($values.COGNITO_ISSUER_URL, [UriKind]::Absolute, [ref]$issuerUri) -or
            $issuerUri.Scheme -cne 'https' -or $issuerUri.Host.EndsWith('.invalid', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Required development OIDC configuration is missing or invalid in repository-root .env.'
    }
}

function Assert-RepositoryOidcConfiguration([string] $Path = $script:envFile) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'Required development OIDC configuration is missing or invalid in repository-root .env.'
    }
    try {
        Assert-RequiredOidcConfiguration (Get-Content -LiteralPath $Path)
    } catch {
        throw 'Required development OIDC configuration is missing or invalid in repository-root .env.'
    }
}

function Assert-Mode([string] $Selected, [bool] $Authorized, [bool] $DisposableConfirmed) {
    if ($Selected -eq 'OfflineTest' -and ($Authorized -or $DisposableConfirmed)) {
        throw 'OfflineTest does not accept runtime authorization switches.'
    }
    if ($Selected -eq 'Preflight' -and ($Authorized -or $DisposableConfirmed)) {
        throw 'Preflight is read-only and does not accept runtime authorization switches.'
    }
    if ($Selected -eq 'AuthorizedStart' -and (-not $Authorized -or -not $DisposableConfirmed)) {
        throw 'AuthorizedStart requires explicit clean-start authorization and disposable synthetic-data confirmation.'
    }
}

function Assert-CleanStartModel([object] $Config) {
    if ($null -eq $Config -or $Config.name -cne $script:projectName -or
            $null -eq $Config.services -or $null -eq $Config.networks -or
            $null -eq $Config.volumes) {
        throw 'The selected Compose model does not match the fixed clean-start project.'
    }
    $actualServices = @($Config.services.PSObject.Properties.Name | Sort-Object)
    $wantedServices = @($script:expectedServices | Sort-Object)
    if (($actualServices -join '|') -cne ($wantedServices -join '|')) {
        throw 'The selected Compose model contains an unexpected service set.'
    }
    $network = $Config.networks.neurovision
    if ($null -eq $network -or $network.name -cne $script:networkName) {
        throw 'The selected Compose model does not use the fixed clean-start network.'
    }
    foreach ($name in $script:volumeNames) {
        $matches = @($Config.volumes.PSObject.Properties.Value | Where-Object { $_.name -ceq $name })
        if ($matches.Count -ne 1) { throw 'The selected Compose model has a volume identity mismatch.' }
    }
    if (@($Config.volumes.PSObject.Properties).Count -ne $script:volumeNames.Count) {
        throw 'The selected Compose model contains an unexpected volume set.'
    }
    $published = @()
    foreach ($serviceName in @('iam-service', 'clinical-service', 'telemetry-service', 'kafka')) {
        $service = $Config.services.$serviceName
        if ($null -eq $service -or @($service.ports).Count -ne 1) {
            throw 'A clean-start published port is missing or ambiguous.'
        }
        $port = $service.ports[0]
        if ($port.host_ip -cne '127.0.0.1' -or $port.mode -cne 'ingress' -or
                $port.protocol -cne 'tcp') {
            throw 'A clean-start published port is not loopback-only TCP.'
        }
        $published += [string] $port.published
    }
    if (($published | Sort-Object) -join '|' -cne '18081|18083|18084|19092') {
        throw 'The selected Compose model has a published-port collision or mismatch.'
    }
    foreach ($serviceName in $script:expectedServices) {
        $service = $Config.services.$serviceName
        $networkAttachment = $service.networks.PSObject.Properties['neurovision']
        if ($null -eq $networkAttachment) {
            throw 'A clean-start service is not attached to the isolated network.'
        }
    }
    $expectedMounts = @(
        @('iam-postgresql', 'iam-postgresql-data', '/var/lib/postgresql'),
        @('clinical-postgresql', 'clinical-postgresql-data', '/var/lib/postgresql'),
        @('telemetry-timescaledb', 'telemetry-timescaledb-data', '/var/lib/postgresql/data'),
        @('kafka', 'kafka-data', '/var/lib/kafka/data')
    )
    foreach ($mount in $expectedMounts) {
        $mounts = @($Config.services.($mount[0]).volumes)
        if ($mounts.Count -ne 1 -or $mounts[0].type -cne 'volume' -or
                $mounts[0].source -cne $mount[1] -or $mounts[0].target -cne $mount[2]) {
            throw 'A clean-start data mount is missing, external, or mismatched.'
        }
    }
}

function Get-CollisionInspectionFailureCode([string] $Subguard) {
    switch -CaseSensitive ($Subguard) {
        'project_containers' { return 'project_containers_inspection_failed' }
        'network' { return 'network_inspection_failed' }
        'volume_iam_postgresql' { return 'volume_iam_postgresql_inspection_failed' }
        'volume_clinical_postgresql' { return 'volume_clinical_postgresql_inspection_failed' }
        'volume_telemetry_timescaledb' { return 'volume_telemetry_timescaledb_inspection_failed' }
        'volume_kafka' { return 'volume_kafka_inspection_failed' }
        'port_18081' { return 'port_18081_inspection_failed' }
        'port_18083' { return 'port_18083_inspection_failed' }
        'port_18084' { return 'port_18084_inspection_failed' }
        'port_19092' { return 'port_19092_inspection_failed' }
        default { return 'collision_inspection_failed' }
    }
}

function Get-ProjectContainerQueryArguments {
    return @('ps', '--all', '--filter', ("label=com.docker.compose.project={0}" -f $script:projectName), '--quiet')
}

function Test-ProjectContainersEmpty([scriptblock] $DockerQuery) {
    $arguments = @(Get-ProjectContainerQueryArguments)
    $ids = @(& $DockerQuery 'docker' $arguments)
    return (@($ids | Where-Object { -not [string]::IsNullOrWhiteSpace([string] $_) }).Count -eq 0)
}

function Assert-NoResourceCollisions([pscustomobject] $Operations) {
    $script:collisionSubguard = 'project_containers'
    if (-not (& $Operations.ProjectIsEmpty)) {
        throw 'The fixed clean-start project already contains containers.'
    }
    $script:collisionSubguard = 'network'
    if (& $Operations.NetworkExists) {
        throw 'The fixed clean-start network already exists.'
    }
    foreach ($volume in $script:volumeNames) {
        $script:collisionSubguard = switch -CaseSensitive ($volume) {
            'telemetry-clean-start-20260926-iam-data' { 'volume_iam_postgresql' }
            'telemetry-clean-start-20260926-clinical-data' { 'volume_clinical_postgresql' }
            'telemetry-clean-start-20260926-telemetry-data' { 'volume_telemetry_timescaledb' }
            'telemetry-clean-start-20260926-kafka-data' { 'volume_kafka' }
            default { 'volume_unknown' }
        }
        if (& $Operations.VolumeExists $volume) {
            throw 'A fixed clean-start volume already exists.'
        }
    }
    foreach ($port in $script:ports) {
        $script:collisionSubguard = 'port_{0}' -f $port
        if (-not (& $Operations.PortAvailable $port)) {
            throw 'A fixed clean-start loopback port is already in use.'
        }
    }
    $script:collisionSubguard = 'complete'
}

function Test-DependencyState([object[]] $State) {
    $requirements = @(
        @('iam-postgresql', 'running', 'healthy'),
        @('iam-service', 'running', $null),
        @('clinical-postgresql', 'running', 'healthy'),
        @('telemetry-timescaledb', 'running', 'healthy'),
        @('kafka', 'running', 'healthy'),
        @('clinical-service', 'running', $null),
        @('telemetry-service', 'running', $null)
    )
    foreach ($expected in $requirements) {
        $matches = @($State | Where-Object { $_.Service -ceq $expected[0] })
        if ($matches.Count -ne 1 -or $matches[0].State -cne $expected[1] -or
                ($null -ne $expected[2] -and $matches[0].Health -cne $expected[2])) {
            return $false
        }
    }
    $provisioning = @($State | Where-Object { $_.Service -ceq 'kafka-init' })
    return ($provisioning.Count -eq 1 -and $provisioning[0].State -ceq 'exited' -and
        [string]$provisioning[0].ExitCode -ceq '0')
}

function Assert-OfflineCase([bool] $Condition, [string] $Name) {
    if (-not $Condition) { throw "Offline clean-start test failed: $Name" }
}

function Assert-Throws([scriptblock] $Action, [string] $Name) {
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-OfflineCase $failed $Name
}

function New-TestModel {
    $services = [ordered]@{}
    foreach ($name in $script:expectedServices) {
        $services[$name] = [pscustomobject]@{
            # Compose config normalizes `networks: [neurovision]` to a present key with a null value.
            networks = [pscustomobject]@{ neurovision = $null }
            ports = @()
            volumes = @()
        }
    }
    $services['clinical-postgresql'].volumes = @([pscustomobject]@{
        type = 'volume'; source = 'clinical-postgresql-data'; target = '/var/lib/postgresql'
    })
    $services['iam-postgresql'].volumes = @([pscustomobject]@{
        type = 'volume'; source = 'iam-postgresql-data'; target = '/var/lib/postgresql'
    })
    $services['iam-service'].ports = @([pscustomobject]@{
        host_ip = '127.0.0.1'; mode = 'ingress'; protocol = 'tcp'; published = '18081'
    })
    $services['telemetry-timescaledb'].volumes = @([pscustomobject]@{
        type = 'volume'; source = 'telemetry-timescaledb-data'; target = '/var/lib/postgresql/data'
    })
    $services['kafka'].volumes = @([pscustomobject]@{
        type = 'volume'; source = 'kafka-data'; target = '/var/lib/kafka/data'
    })
    $services['clinical-service'].ports = @([pscustomobject]@{
        host_ip = '127.0.0.1'; mode = 'ingress'; protocol = 'tcp'; published = '18083'
    })
    $services['telemetry-service'].ports = @([pscustomobject]@{
        host_ip = '127.0.0.1'; mode = 'ingress'; protocol = 'tcp'; published = '18084'
    })
    $services['kafka'].ports = @([pscustomobject]@{
        host_ip = '127.0.0.1'; mode = 'ingress'; protocol = 'tcp'; published = '19092'
    })
    return [pscustomobject]@{
        name = $script:projectName
        services = [pscustomobject]$services
        networks = [pscustomobject]@{ neurovision = [pscustomobject]@{ name = $script:networkName } }
        volumes = [pscustomobject]@{
            'iam-postgresql-data' = [pscustomobject]@{ name = $script:volumeNames[0] }
            'clinical-postgresql-data' = [pscustomobject]@{ name = $script:volumeNames[1] }
            'telemetry-timescaledb-data' = [pscustomobject]@{ name = $script:volumeNames[2] }
            'kafka-data' = [pscustomobject]@{ name = $script:volumeNames[3] }
        }
    }
}

function Invoke-OfflineTests {
    $good = New-TestModel
    Assert-CleanStartModel $good
    $wrongProject = New-TestModel
    $wrongProject.name = 'neurovision-rehab'
    Assert-Throws { Assert-CleanStartModel $wrongProject } 'existing-project-name-refused'
    $wrongNetwork = New-TestModel
    $wrongNetwork.networks.neurovision.name = 'neurovision-compose'
    Assert-Throws { Assert-CleanStartModel $wrongNetwork } 'existing-network-refused'
    $missingAttachment = New-TestModel
    $missingAttachment.services.'clinical-service'.networks.PSObject.Properties.Remove('neurovision')
    $missingAttachment.services.'clinical-service'.networks | Add-Member -MemberType NoteProperty -Name other -Value $null
    Assert-Throws { Assert-CleanStartModel $missingAttachment } 'missing-service-network-refused'
    $wrongVolume = New-TestModel
    $wrongVolume.volumes.'telemetry-timescaledb-data'.name = 'neurovision-rehab_telemetry-timescaledb-data'
    Assert-Throws { Assert-CleanStartModel $wrongVolume } 'existing-persisted-telemetry-volume-refused'
    $wrongPort = New-TestModel
    $wrongPort.services.'telemetry-service'.ports[0].published = '8084'
    Assert-Throws { Assert-CleanStartModel $wrongPort } 'existing-telemetry-port-refused'

    Assert-RequiredOidcConfiguration @('COGNITO_CLIENT_ID=offline-client-123', 'COGNITO_ISSUER_URL=https://idp.test/tenant')
    foreach ($invalidEnv in @(
            @('COGNITO_ISSUER_URL=https://idp.test/tenant'),
            @('COGNITO_CLIENT_ID=offline-client-123'),
            @('COGNITO_CLIENT_ID=', 'COGNITO_ISSUER_URL=https://idp.test/tenant'),
            @('COGNITO_CLIENT_ID=clean-start-placeholder', 'COGNITO_ISSUER_URL=https://idp.test/tenant'),
            @('COGNITO_CLIENT_ID=offline-client-123', 'COGNITO_ISSUER_URL=https://issuer.invalid/clean-start'))) {
        Assert-Throws { Assert-RequiredOidcConfiguration $invalidEnv } 'missing-or-placeholder-oidc-refused'
    }
    $missingEnvPath = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    Assert-Throws { Assert-RepositoryOidcConfiguration $missingEnvPath } 'missing-env-file-refused'

    $clear = [pscustomobject]@{
        ProjectIsEmpty = { return $true }
        NetworkExists = { return $false }
        VolumeExists = { param($Name) return $false }
        PortAvailable = { param($Port) return $true }
    }
    Assert-NoResourceCollisions $clear
    foreach ($collision in @('project', 'network', 'volume', 'port')) {
        $ops = [pscustomobject]@{
            ProjectIsEmpty = { return ($collision -ne 'project') }
            NetworkExists = { return ($collision -eq 'network') }
            VolumeExists = { param($Name) return ($collision -eq 'volume') }
            PortAvailable = { param($Port) return ($collision -ne 'port') }
        }
        Assert-Throws { Assert-NoResourceCollisions $ops } ("collision-{0}-refused" -f $collision)
    }
    $expectedDiagnosticCodes = [ordered]@{
        project_containers = 'project_containers_inspection_failed'
        network = 'network_inspection_failed'
        volume_iam_postgresql = 'volume_iam_postgresql_inspection_failed'
        volume_clinical_postgresql = 'volume_clinical_postgresql_inspection_failed'
        volume_telemetry_timescaledb = 'volume_telemetry_timescaledb_inspection_failed'
        volume_kafka = 'volume_kafka_inspection_failed'
        port_18081 = 'port_18081_inspection_failed'
        port_18083 = 'port_18083_inspection_failed'
        port_18084 = 'port_18084_inspection_failed'
        port_19092 = 'port_19092_inspection_failed'
    }
    foreach ($subguard in $expectedDiagnosticCodes.Keys) {
        Assert-OfflineCase ((Get-CollisionInspectionFailureCode $subguard) -ceq $expectedDiagnosticCodes[$subguard]) `
            ("collision-diagnostic-{0}" -f $subguard)
    }
    $script:capturedProjectExecutable = ''
    $projectEmpty = Test-ProjectContainersEmpty {
        param([string] $Executable, [string[]] $Arguments)
        $script:capturedProjectExecutable = $Executable
        $script:capturedProjectQuery = @($Arguments)
        return @()
    }
    Assert-OfflineCase $projectEmpty 'project-container-empty-result-accepted'
    Assert-OfflineCase ($script:capturedProjectExecutable -ceq 'docker') 'project-query-uses-docker-cli'
    Assert-OfflineCase (($script:capturedProjectQuery -join '|') -ceq
        ('ps|--all|--filter|label=com.docker.compose.project={0}|--quiet' -f $script:projectName)) `
        'project-query-uses-exact-disposable-label-only'
    Assert-OfflineCase (@($script:capturedProjectQuery | Where-Object { $_ -like 'label=com.docker.compose.project=*' }).Count -eq 1) `
        'project-query-has-one-project-label-filter'
    $networkQuery = Get-NetworkListArguments $script:networkName
    $volumeQuery = Get-VolumeListArguments 'telemetry-clean-start-20260926-telemetry-data'
    Assert-OfflineCase (($networkQuery -join '|') -ceq 'network|ls|--filter|name=^telemetry-clean-start-20260926-network$|--quiet') `
        'network-query-uses-template-free-quiet-output'
    Assert-OfflineCase (($volumeQuery -join '|') -ceq 'volume|ls|--filter|name=^telemetry-clean-start-20260926-telemetry-data$|--quiet') `
        'volume-query-uses-template-free-quiet-output'
    Assert-OfflineCase (@($networkQuery + $volumeQuery | Where-Object { $_ -eq '--format' -or $_ -match '\{\{' }).Count -eq 0) `
        'network-volume-queries-never-pass-go-templates'
    $projectCollisionOps = [pscustomobject]@{
        ProjectIsEmpty = {
            Test-ProjectContainersEmpty {
                param([string] $Executable, [string[]] $Arguments)
                return @('fixed-test-container-id')
            }
        }
        NetworkExists = { return $false }
        VolumeExists = { param($Name) return $false }
        PortAvailable = { param($Port) return $true }
    }
    Assert-Throws { Assert-NoResourceCollisions $projectCollisionOps } 'project-label-query-nonempty-results-refused'
    $script:collisionSubguard = 'not_started'
    try {
        Assert-NoResourceCollisions ([pscustomobject]@{
            ProjectIsEmpty = { throw 'PRIVATE_DIAGNOSTIC_MUST_NOT_ESCAPE' }
            NetworkExists = { return $false }
            VolumeExists = { param($Name) return $false }
            PortAvailable = { param($Port) return $true }
        })
    } catch {
        Assert-OfflineCase ($script:collisionSubguard -ceq 'project_containers') 'collision-subguard-set-before-operation'
        Assert-OfflineCase ((Get-CollisionInspectionFailureCode $script:collisionSubguard) -ceq
            'project_containers_inspection_failed') 'unexpected-collision-error-mapped-safely'
        Assert-OfflineCase ((Get-CollisionInspectionFailureCode $script:collisionSubguard) -notmatch 'PRIVATE|DIAGNOSTIC') `
            'unexpected-collision-error-does-not-leak'
    }
    Assert-Throws { Assert-Mode 'OfflineTest' $true $true } 'offline-authorizations-refused'
    Assert-Throws { Assert-Mode 'Preflight' $true $true } 'preflight-authorizations-refused'
    Assert-Throws { Assert-Mode 'AuthorizedStart' $false $true } 'start-requires-authorization'
    Assert-Throws { Assert-Mode 'AuthorizedStart' $true $false } 'start-requires-disposable-confirmation'
    Assert-Mode 'AuthorizedStart' $true $true
    $state = @(
        [pscustomobject]@{ Service='iam-postgresql'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='iam-service'; State='running'; Health='' },
        [pscustomobject]@{ Service='clinical-postgresql'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='telemetry-timescaledb'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='kafka'; State='running'; Health='healthy' },
        [pscustomobject]@{ Service='kafka-init'; State='exited'; ExitCode='0' },
        [pscustomobject]@{ Service='clinical-service'; State='running'; Health='' },
        [pscustomobject]@{ Service='telemetry-service'; State='running'; Health='' }
    )
    Assert-OfflineCase (Test-DependencyState $state) 'clean-start-runtime-state-accepted'
    $state[2].Health = 'starting'
    Assert-OfflineCase (-not (Test-DependencyState $state)) 'unhealthy-runtime-dependency-refused'
    $state[2].Health = 'healthy'
    $state[5].ExitCode = '1'
    Assert-OfflineCase (-not (Test-DependencyState $state)) 'failed-kafka-initialization-refused'
    Write-Output 'OFFLINE_TESTS_PASSED: model-identity=5; collision-guards=5; collision-diagnostics=10; project-label-query=4; resource-query-shapes=3; authorization-guards=4; oidc-guards=7; readiness-predicates=3; docker-lifecycle-calls=0'
}

function Invoke-Native([string] $Executable, [string[]] $Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Executable @Arguments 2>$null)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw 'A required local Docker inspection or scoped start failed.' }
    return @($output)
}

function Invoke-Compose([string[]] $Arguments) {
    return Invoke-Native 'docker' (@('compose', '--env-file', $script:envFile, '-p', $script:projectName,
        '-f', $script:composeFile) + $Arguments)
}

function Get-CleanStartModel {
    $lines = Invoke-Compose @('config', '--format', 'json')
    try { return (($lines -join "`n") | ConvertFrom-Json) } catch {
        throw 'The clean-start Compose model could not be safely inspected.'
    }
}

function Get-NetworkListArguments([string] $Name) {
    return @('network', 'ls', '--filter', ("name=^{0}$" -f $Name), '--quiet')
}

function Get-VolumeListArguments([string] $Name) {
    return @('volume', 'ls', '--filter', ("name=^{0}$" -f $Name), '--quiet')
}

function Test-PortAvailable([int] $Port) {
    $listener = $null
    try {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $true
    } catch { return $false } finally {
        if ($null -ne $listener) { $listener.Stop() }
    }
}

function Get-DockerOperations {
    return [pscustomobject]@{
        ProjectIsEmpty = {
            return (Test-ProjectContainersEmpty {
                param([string] $Executable, [string[]] $Arguments)
                Invoke-Native $Executable $Arguments
            })
        }
        NetworkExists = {
            $ids = Invoke-Native 'docker' (Get-NetworkListArguments $script:networkName)
            return (@($ids | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -gt 0)
        }
        VolumeExists = {
            param([string] $Name)
            $ids = Invoke-Native 'docker' (Get-VolumeListArguments $Name)
            return (@($ids | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -gt 0)
        }
        PortAvailable = { param([int] $Port) return (Test-PortAvailable $Port) }
        GetModel = { Get-CleanStartModel }
    }
}

function Invoke-CleanStart([pscustomobject] $Operations, [scriptblock] $Start) {
    $script:stage = 'compose_file'
    if (-not (Test-Path -LiteralPath $script:composeFile -PathType Leaf)) {
        throw 'The isolated clean-start Compose file is unavailable.'
    }
    Assert-RepositoryOidcConfiguration
    $script:stage = 'compose_model'
    $config = & $Operations.GetModel
    Assert-CleanStartModel $config
    $script:stage = 'collision_guard'
    Assert-NoResourceCollisions $Operations
    $script:stage = 'compose_start'
    & $Start
}

function New-LiveStart {
    return {
        [void] (Invoke-Compose @('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180',
            'clinical-postgresql', 'telemetry-timescaledb', 'kafka', 'kafka-init', 'clinical-service', 'telemetry-service'))
    }
}

function Get-ComposeState {
    $lines = Invoke-Compose @('ps', '--all', '--format', 'json')
    $items = @()
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        try { $items += ($line | ConvertFrom-Json) } catch {
            throw 'The clean-start Compose runtime state could not be safely inspected.'
        }
    }
    return ,@($items)
}

function Test-TelemetryReady {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri 'http://127.0.0.1:18084/q/health/ready' `
            -TimeoutSec 5 -ErrorAction Stop
        return ([int]$response.StatusCode -eq 200)
    } catch { return $false }
}

function Wait-ForCleanStart([int] $TimeoutSeconds = 180) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $script:stage = 'dependency_readiness'
        if (Test-DependencyState (Get-ComposeState)) {
            $script:stage = 'telemetry_readiness'
            if (Test-TelemetryReady) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw 'The isolated stack did not reach its bounded clean-start readiness condition.'
}

try {
    Assert-Mode $Mode ([bool]$AuthorizeCleanStart) ([bool]$ConfirmDisposableSyntheticEnvironment)
    if ($Mode -eq 'OfflineTest') {
        Invoke-OfflineTests
        exit 0
    }
    $operations = Get-DockerOperations
    if ($Mode -eq 'Preflight') {
        Invoke-CleanStart $operations {}
        Write-Output 'PREFLIGHT_PASSED: isolated-model=verified; collisions=none; no-start-or-cleanup=performed'
        exit 0
    }
    Invoke-CleanStart $operations (New-LiveStart)
    Wait-ForCleanStart
    Write-Output 'CLEAN_START_RUNTIME_PASSED: dependencies=healthy; kafka-init=completed; telemetry-readiness=200; authenticated-ingestion=not-run; cleanup=not-performed'
} catch {
    $known = @('mode_guard', 'compose_file', 'compose_model', 'collision_guard', 'compose_start', 'dependency_readiness', 'telemetry_readiness')
    $safeStage = if ($script:stage -in $known) { $script:stage } else { 'unknown' }
    $guard = switch -CaseSensitive ($_.Exception.Message) {
        'OfflineTest does not accept runtime authorization switches.' { 'offline_authorization_refused' }
        'Preflight is read-only and does not accept runtime authorization switches.' { 'preflight_authorization_refused' }
        'AuthorizedStart requires explicit clean-start authorization and disposable synthetic-data confirmation.' { 'start_authorization_required' }
        'The isolated clean-start Compose file is unavailable.' { 'compose_file_unavailable' }
        'Required development OIDC configuration is missing or invalid in repository-root .env.' { 'oidc_configuration_required' }
        'The selected Compose model does not match the fixed clean-start project.' { 'project_mismatch' }
        'The selected Compose model does not use the fixed clean-start network.' { 'network_mismatch' }
        'The selected Compose model has a volume identity mismatch.' { 'volume_mismatch' }
        'The selected Compose model has a published-port collision or mismatch.' { 'port_mismatch' }
        'The fixed clean-start project already contains containers.' { 'project_collision' }
        'The fixed clean-start network already exists.' { 'network_collision' }
        'A fixed clean-start volume already exists.' { 'volume_collision' }
        'A fixed clean-start loopback port is already in use.' { 'port_collision' }
        'The clean-start Compose runtime state could not be safely inspected.' { 'runtime_state_invalid' }
        'The isolated stack did not reach its bounded clean-start readiness condition.' { 'readiness_timeout' }
        default { 'precondition_or_operation_failed' }
    }
    $safeSubguard = ''
    if ($script:stage -eq 'collision_guard') {
        $safeSubguard = switch -CaseSensitive ($script:collisionSubguard) {
            'project_containers' { 'project_containers' }
            'network' { 'network' }
            'volume_iam_postgresql' { 'volume_iam_postgresql' }
            'volume_clinical_postgresql' { 'volume_clinical_postgresql' }
            'volume_telemetry_timescaledb' { 'volume_telemetry_timescaledb' }
            'volume_kafka' { 'volume_kafka' }
            'port_18081' { 'port_18081' }
            'port_18083' { 'port_18083' }
            'port_18084' { 'port_18084' }
            'port_19092' { 'port_19092' }
            default { 'unknown' }
        }
        if ($guard -eq 'precondition_or_operation_failed') {
            $guard = Get-CollisionInspectionFailureCode $safeSubguard
        }
    }
    $diagnostic = if ($script:stage -eq 'collision_guard') {
        "Telemetry clean-start stopped safely; stage={0}; guard={1}; subguard={2}; no cleanup or ingestion was attempted." -f $safeStage, $guard, $safeSubguard
    } else {
        "Telemetry clean-start stopped safely; stage={0}; guard={1}; no cleanup or ingestion was attempted." -f $safeStage, $guard
    }
    Write-Error -Message $diagnostic -ErrorAction Continue
    exit 1
}
