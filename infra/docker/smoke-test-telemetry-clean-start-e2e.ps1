[CmdletBinding()]
param(
    [ValidateSet('OfflineTest', 'ResumeIsolatedCore', 'StartIsolatedIam', 'DoctorSetup', 'PatientSession', 'SubmitBatch', 'Verify')]
    [string] $Mode = 'OfflineTest',
    [switch] $AuthorizeIsolatedCoreResume,
    [switch] $AuthorizeIsolatedIamStart,
    [switch] $ConfirmDisposableSyntheticEnvironment
)

$ErrorActionPreference = 'Stop'
$script:Project = 'telemetry-clean-start-20260926'
$script:NetworkName = 'telemetry-clean-start-20260926-network'
$script:CoreServices = @('clinical-postgresql','telemetry-timescaledb','kafka','kafka-init','clinical-service','telemetry-service')
$script:CoreVolumes = @(
    @('clinical-postgresql-data','telemetry-clean-start-20260926-clinical-data'),
    @('telemetry-timescaledb-data','telemetry-clean-start-20260926-telemetry-data'),
    @('kafka-data','telemetry-clean-start-20260926-kafka-data')
)
$script:CorePorts = @(18083,18084,19092)
$script:ComposeFile = Join-Path $PSScriptRoot 'telemetry-clean-start.compose.yaml'
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:EnvFile = Join-Path $script:Root '.env'
$script:Fixture = Join-Path $script:Root 'telemetry-service/src/test/resources/fixtures/telemetry/v1/dual-typed-batch.json'
$script:StateFile = Join-Path $env:TEMP 'telemetry-clean-start-20260926-tcs6-state.json'
$script:Redirect = 'http://localhost:5173/auth/callback'
$script:ListenerPrefix = 'http://localhost:5173/'
$script:Stage = 'mode_guard'

function Assert-StageState([string] $SelectedMode, [pscustomobject] $State) {
    if ($null -eq $State) { throw 'TCS-6 state is unavailable.' }
    switch ($SelectedMode) {
        'PatientSession' {
            if (-not $State.invitationCompleted -or $State.sessionAttempted) {
                throw 'PatientSession is not available in the current one-way stage.'
            }
        }
        'SubmitBatch' {
            if (-not $State.sessionCreated -or $State.ingestionAttempted) {
                throw 'SubmitBatch is not available in the current one-way stage.'
            }
        }
        'Verify' {
            if (-not $State.ingestionAccepted) {
                throw 'Verify requires one accepted synthetic batch.'
            }
        }
    }
}

function Get-IamStartArguments {
    return @('compose','--env-file','.env','-p',$script:Project,'-f',$script:ComposeFile,
        'up','-d','--no-build','--pull','never','iam-postgresql','iam-service')
}

function Get-NetworkInspectArguments([string] $Name) {
    return @('network','inspect',$Name)
}

function ConvertFrom-DockerJsonArray([string] $Json, [string] $SafeFailure) {
    if ([string]::IsNullOrWhiteSpace($Json) -or $Json.TrimStart()[0] -ne '[') { throw $SafeFailure }
    try {
        $parsed=ConvertFrom-Json -InputObject $Json -ErrorAction Stop
        return ,@($parsed)
    } catch { throw $SafeFailure }
}

function Get-ProjectContainerArguments {
    return @('ps','--all','--filter',"label=com.docker.compose.project=$($script:Project)",'--quiet')
}

function Get-ProjectVolumeArguments {
    return @('volume','ls','--filter',"label=com.docker.compose.project=$($script:Project)",'--quiet')
}

function Get-NamedVolumeArguments([string] $Name) {
    return @('volume','ls','--filter',"name=^$Name$",'--quiet')
}

function Test-ExactResourceNames([object[]] $Actual, [string[]] $Expected) {
    $actualNames=@($Actual | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object)
    $expectedNames=@($Expected | Sort-Object)
    return ($actualNames.Count -eq $expectedNames.Count -and ($actualNames -join '|') -ceq ($expectedNames -join '|'))
}

function Test-NetworkIdentityDocument([string] $Json, [string] $ExpectedName, [string] $ExpectedProject) {
    try {
        $networks=ConvertFrom-DockerJsonArray $Json 'The network identity document was invalid.'
        return ($networks.Count -eq 1 -and $networks[0].Name -ceq $ExpectedName -and
            $networks[0].Labels.'com.docker.compose.project' -ceq $ExpectedProject)
    } catch { return $false }
}

function Assert-CoreResumeAuthorization([bool] $Authorized, [bool] $Confirmed) {
    if (-not $Authorized -or -not $Confirmed) { throw 'Isolated core resume requires separate authorization and disposable-environment confirmation.' }
}

function Get-CoreResumeCommandPlan {
    return @(
        [pscustomobject]@{
            Stage='dependencies'
            Arguments=@('compose','--env-file',$script:EnvFile,'-p',$script:Project,'-f',$script:ComposeFile,
                'start','clinical-postgresql','telemetry-timescaledb','kafka')
        },
        [pscustomobject]@{
            Stage='applications'
            Arguments=@('compose','--env-file',$script:EnvFile,'-p',$script:Project,'-f',$script:ComposeFile,
                'start','clinical-service','telemetry-service')
        }
    )
}

function Get-CoreResumeDiagnosticCode([string] $Guard) {
    switch -CaseSensitive ($Guard) {
        'core_resume_authorization' { return 'core_resume_authorization_failed' }
        'core_resume_compose_config' { return 'core_resume_compose_config_validation_failed' }
        'core_resume_network_identity' { return 'core_resume_network_identity_failed' }
        'core_resume_volume_set' { return 'core_resume_volume_set_failed' }
        'core_resume_volume_clinical_postgresql_data' { return 'core_resume_volume_clinical_postgresql_failed' }
        'core_resume_volume_telemetry_timescaledb_data' { return 'core_resume_volume_telemetry_timescaledb_failed' }
        'core_resume_volume_kafka_data' { return 'core_resume_volume_kafka_failed' }
        'core_resume_iam_absence' { return 'core_resume_iam_absence_check_failed' }
        'core_resume_containers' { return 'core_resume_container_inspection_failed' }
        'core_resume_stopped_state' { return 'core_resume_stopped_state_mismatch' }
        'core_resume_port_18083' { return 'core_resume_port_18083_unavailable' }
        'core_resume_port_18084' { return 'core_resume_port_18084_unavailable' }
        'core_resume_port_19092' { return 'core_resume_port_19092_unavailable' }
        'core_resume_dependency_start' { return 'core_resume_dependency_start_failed' }
        'core_resume_dependency_health' { return 'core_resume_dependency_health_failed' }
        'core_resume_application_start' { return 'core_resume_application_start_failed' }
        'core_resume_application_state' { return 'core_resume_application_state_failed' }
        'core_resume_readiness' { return 'core_resume_readiness_failed' }
        default { return 'core_resume_guard_failed' }
    }
}

function Format-CoreResumeDiagnostic([string] $Guard, [string] $IgnoredExceptionText) {
    return ("TCS-6 stopped safely; diagnostic={0}; no cleanup or automatic retry was attempted." -f (Get-CoreResumeDiagnosticCode $Guard))
}

function Get-CoreContainerDocuments {
    $ids=@(& docker @(Get-ProjectContainerArguments) 2>$null)
    if ($LASTEXITCODE -ne 0 -or $ids.Count -ne $script:CoreServices.Count) { throw 'The fixed six-service project inventory did not match.' }
    $documents=@(& docker inspect @ids 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'The fixed project containers could not be inspected.' }
    return (ConvertFrom-DockerJsonArray ($documents -join [Environment]::NewLine) 'The fixed project container details were not valid.')
}

function Assert-CoreContainerSet([object[]] $Containers, [bool] $RequireStopped) {
    if ($Containers.Count -ne $script:CoreServices.Count) { throw 'The fixed six-service project inventory did not match.' }
    $actual=@()
    foreach ($container in $Containers) {
        if ($container.Config.Labels.'com.docker.compose.project' -cne $script:Project) { throw 'A project container label did not match.' }
        $service=[string]$container.Config.Labels.'com.docker.compose.service'
        if ([string]::IsNullOrWhiteSpace($service)) { throw 'A project container service label was missing.' }
        $actual += $service
        if ($RequireStopped) {
            if ($container.State.Status -cne 'exited' -or $container.HostConfig.RestartPolicy.Name -cne 'no') {
                throw 'The six core containers were not in the expected stopped state.'
            }
            if ($service -eq 'kafka-init' -and [int]$container.State.ExitCode -ne 0) {
                throw 'The existing Kafka initialization result was not successful.'
            }
        } else {
            switch -CaseSensitive ($service) {
                { $_ -in @('clinical-postgresql','telemetry-timescaledb','kafka') } {
                    if ($container.State.Status -cne 'running' -or $container.State.Health.Status -cne 'healthy') { throw 'A core dependency was not healthy.' }
                }
                'kafka-init' { if ($container.State.Status -cne 'exited' -or [int]$container.State.ExitCode -ne 0) { throw 'Kafka initialization did not exit successfully.' } }
                { $_ -in @('clinical-service','telemetry-service') } { if ($container.State.Status -cne 'running') { throw 'A core application service was not running.' } }
                default { throw 'An unexpected service was present in the project inventory.' }
            }
        }
    }
    $actualSorted=@($actual | Sort-Object) -join '|'
    $expectedSorted=@($script:CoreServices | Sort-Object) -join '|'
    if ($actualSorted -cne $expectedSorted) { throw 'The project service set did not match.' }
}

function Test-IamStartCoreStateJson([string] $Json) {
    try {
        $containers=ConvertFrom-DockerJsonArray $Json 'The project container details were invalid.'
        Assert-CoreContainerSet $containers $false
        return $true
    } catch { return $false }
}

function Assert-CoreDependencyPhase([object[]] $Containers) {
    if ($Containers.Count -ne $script:CoreServices.Count) { throw 'The fixed six-service project inventory did not match.' }
    $actual=@()
    foreach ($container in $Containers) {
        if ($container.Config.Labels.'com.docker.compose.project' -cne $script:Project) { throw 'A project container label did not match.' }
        $service=[string]$container.Config.Labels.'com.docker.compose.service'
        $actual += $service
        switch -CaseSensitive ($service) {
            { $_ -in @('clinical-postgresql','telemetry-timescaledb','kafka') } {
                if ($container.State.Status -cne 'running' -or $container.State.Health.Status -cne 'healthy') { throw 'A core dependency was not healthy.' }
            }
            'kafka-init' { if ($container.State.Status -cne 'exited' -or [int]$container.State.ExitCode -ne 0) { throw 'The existing Kafka initialization result changed.' } }
            { $_ -in @('clinical-service','telemetry-service') } {
                if ($container.State.Status -cne 'exited' -or $container.HostConfig.RestartPolicy.Name -cne 'no') { throw 'An application started before its stage.' }
            }
            default { throw 'An unexpected service was present in the project inventory.' }
        }
    }
    $actualSorted=@($actual | Sort-Object) -join '|'
    $expectedSorted=@($script:CoreServices | Sort-Object) -join '|'
    if ($actualSorted -cne $expectedSorted) { throw 'The project service set did not match.' }
}

function New-FakeCoreContainers([bool] $Running) {
    $result=@()
    foreach ($service in $script:CoreServices) {
        $status=if ($Running -and $service -ne 'kafka-init') { 'running' } else { 'exited' }
        $health=$null
        $exitCode=if ($service -eq 'kafka-init') { 0 } else { 1 }
        if ($Running -and $service -in @('clinical-postgresql','telemetry-timescaledb','kafka')) {
            $health=[pscustomobject]@{ Status='healthy' }
        }
        $result += [pscustomobject]@{
            Config=[pscustomobject]@{ Labels=@{
                'com.docker.compose.project'=$script:Project
                'com.docker.compose.service'=$service
            }}
            State=[pscustomobject]@{ Status=$status; ExitCode=$exitCode; Health=$health }
            HostConfig=[pscustomobject]@{ RestartPolicy=[pscustomobject]@{ Name='no' } }
        }
    }
    return ,$result
}

function New-FakeCoreDependencyContainers {
    $result=New-FakeCoreContainers $false
    foreach ($container in $result) {
        $service=$container.Config.Labels.'com.docker.compose.service'
        if ($service -in @('clinical-postgresql','telemetry-timescaledb','kafka')) {
            $container.State.Status='running'
            $container.State.Health=[pscustomobject]@{ Status='healthy' }
        }
    }
    return ,$result
}

function Test-VolumeIdentity([string] $Name, [string] $Project, [string] $LogicalName) {
    $rows=@(& docker volume inspect $Name 2>$null)
    if ($LASTEXITCODE -ne 0) { return $false }
    return (Test-VolumeIdentityDocument ($rows -join [Environment]::NewLine) $Name $Project $LogicalName)
}

function Test-VolumeIdentityDocument([string] $Json, [string] $Name, [string] $Project, [string] $LogicalName) {
    try {
        $items=ConvertFrom-DockerJsonArray $Json 'The volume identity document was invalid.'
        return ($items.Count -eq 1 -and $items[0].Name -ceq $Name -and
            $items[0].Labels.'com.docker.compose.project' -ceq $Project -and
            $items[0].Labels.'com.docker.compose.volume' -ceq $LogicalName)
    } catch { return $false }
}

function Get-ComposeServiceContainerArguments([string] $Service) {
    return @('ps','--all','--filter',"label=com.docker.compose.project=$($script:Project)",
        '--filter',"label=com.docker.compose.service=$Service",'--quiet')
}

function Test-FixedLoopbackPort([int] $Port) {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start(); return $true } catch { return $false } finally { $listener.Stop() }
}

function Invoke-CoreResume {
    $script:Stage='core_resume_authorization'
    Assert-CoreResumeAuthorization ([bool]$AuthorizeIsolatedCoreResume) ([bool]$ConfirmDisposableSyntheticEnvironment)
    $script:Stage='core_resume_compose_config'
    $compose=@('compose','--env-file',$script:EnvFile,'-p',$script:Project,'-f',$script:ComposeFile)
    & docker @compose config -q 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated Compose model did not validate.' }
    $script:Stage='core_resume_network_identity'
    $networkArgs=Get-NetworkInspectArguments $script:NetworkName
    $networkJson=(& docker @networkArgs 2>$null) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or -not (Test-NetworkIdentityDocument $networkJson $script:NetworkName $script:Project)) {
        throw 'The isolated network identity did not match.'
    }
    $script:Stage='core_resume_volume_set'
    $projectVolumes=@(& docker @(Get-ProjectVolumeArguments) 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'The isolated project volumes could not be inspected.' }
    $expectedVolumeNames=@($script:CoreVolumes | ForEach-Object { $_[1] } | Sort-Object)
    if (-not (Test-ExactResourceNames $projectVolumes $expectedVolumeNames)) { throw 'The exact three core volumes did not match.' }
    foreach ($volume in $script:CoreVolumes) {
        $script:Stage='core_resume_volume_' + ($volume[0] -replace '-','_')
        if (-not (Test-VolumeIdentity $volume[1] $script:Project $volume[0])) { throw 'A fixed core data volume identity did not match.' }
    }
    $script:Stage='core_resume_iam_absence'
    foreach ($service in @('iam-postgresql','iam-service')) {
        $iamArgs=Get-ComposeServiceContainerArguments $service
        $iamContainers=@(& docker @iamArgs 2>$null)
        if ($LASTEXITCODE -ne 0 -or @($iamContainers | Where-Object { $_ }).Count -gt 0) { throw 'An IAM container already exists.' }
    }
    $iamVolume=@(& docker @(Get-NamedVolumeArguments 'telemetry-clean-start-20260926-iam-data') 2>$null)
    if ($LASTEXITCODE -ne 0 -or @($iamVolume | Where-Object { $_ -ceq 'telemetry-clean-start-20260926-iam-data' }).Count -gt 0) { throw 'An IAM volume already exists.' }
    $script:Stage='core_resume_containers'
    $containers=Get-CoreContainerDocuments
    $script:Stage='core_resume_stopped_state'
    Assert-CoreContainerSet $containers $true
    foreach ($port in $script:CorePorts) {
        $script:Stage='core_resume_port_' + $port
        if (-not (Test-FixedLoopbackPort $port)) { throw 'A fixed isolated core port was unavailable.' }
    }
    $plan=Get-CoreResumeCommandPlan
    $script:Stage='core_resume_dependency_start'
    $dockerArgs=@($plan[0].Arguments)
    $null=& docker @dockerArgs 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated core dependency start failed; no retry or cleanup was attempted.' }
    $script:Stage='core_resume_dependency_health'
    $dependencyDeadline=[Diagnostics.Stopwatch]::StartNew(); $dependenciesReady=$false
    while ($dependencyDeadline.Elapsed.TotalSeconds -lt 90) {
        try {
            Assert-CoreDependencyPhase (Get-CoreContainerDocuments)
            $dependenciesReady=$true
            break
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $dependenciesReady) { throw 'The core dependencies did not become healthy; applications remain unstarted.' }
    $script:Stage='core_resume_application_start'
    $dockerArgs=@($plan[1].Arguments)
    $null=& docker @dockerArgs 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated core application start failed; no retry or cleanup was attempted.' }
    $script:Stage='core_resume_application_state'
    $applicationDeadline=[Diagnostics.Stopwatch]::StartNew(); $applicationsReady=$false
    while ($applicationDeadline.Elapsed.TotalSeconds -lt 90) {
        try {
            Assert-CoreContainerSet (Get-CoreContainerDocuments) $false
            $applicationsReady=$true
            break
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $applicationsReady) { throw 'The core applications did not reach the required state.' }
    $script:Stage='core_resume_readiness'
    $deadline=[Diagnostics.Stopwatch]::StartNew()
    while ($deadline.Elapsed.TotalSeconds -lt 90) {
        try {
            $response=Invoke-WebRequest -UseBasicParsing -Method Get -Uri 'http://127.0.0.1:18084/q/health/ready' -TimeoutSec 3 -ErrorAction Stop
            if ([int]$response.StatusCode -eq 200) {
                Write-Host 'ISOLATED_CORE_READY: status=200; lifecycle=core-services-only.'
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'The isolated core did not reach bounded readiness; no cleanup or retry was attempted.'
}

function Assert-IamStartAuthorization([bool] $Authorized, [bool] $Confirmed) {
    if (-not $Authorized -or -not $Confirmed) { throw 'Isolated IAM start requires its separate authorization and disposable-environment confirmation.' }
}

function Get-IamStartDiagnosticCode([string] $Guard) {
    switch -CaseSensitive ($Guard) {
        'iam_start_authorization' { return 'iam_start_authorization_failed' }
        'iam_start_settings' { return 'iam_start_settings_validation_failed' }
        'iam_start_compose_config' { return 'iam_start_compose_config_validation_failed' }
        'iam_start_network_identity' { return 'iam_start_network_identity_failed' }
        'iam_start_existing_services' { return 'iam_start_existing_services_inspection_failed' }
        'iam_start_container_absence' { return 'iam_start_container_absence_check_failed' }
        'iam_start_volume_absence' { return 'iam_start_volume_absence_check_failed' }
        'iam_start_port_availability' { return 'iam_start_port_availability_failed' }
        'iam_start' { return 'iam_start_operation_failed' }
        'iam_start_readiness' { return 'iam_start_readiness_failed' }
        default { return 'iam_start_guard_failed' }
    }
}

function Format-IamStartDiagnostic([string] $Guard, [string] $IgnoredExceptionText) {
    return ("TCS-6 stopped safely; diagnostic={0}; no automatic replay or cleanup was attempted." -f (Get-IamStartDiagnosticCode $Guard))
}

function Invoke-IamStart {
    $script:Stage='iam_start_authorization'
    Assert-IamStartAuthorization ([bool]$AuthorizeIsolatedIamStart) ([bool]$ConfirmDisposableSyntheticEnvironment)
    $script:Stage='iam_start_settings'
    $settings=Get-Settings
    $compose=@('compose','--env-file',$script:EnvFile,'-p',$script:Project,'-f',$script:ComposeFile)
    # Render only to /dev/null-equivalent quiet validation; never print resolved configuration.
    $script:Stage='iam_start_compose_config'
    & docker @compose config -q 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated IAM Compose model did not validate.' }
    $script:Stage='iam_start_network_identity'
    $networkName=$script:NetworkName
    $networkArgs=Get-NetworkInspectArguments $networkName
    $networkJson=(& docker @networkArgs 2>$null) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or -not (Test-NetworkIdentityDocument $networkJson $networkName $script:Project)) {
        throw 'The existing disposable project network identity did not match.'
    }
    $script:Stage='iam_start_existing_services'
    $containers=Get-CoreContainerDocuments
    if ($LASTEXITCODE -ne 0) { throw 'The disposable project containers could not be safely inspected.' }
    Assert-CoreContainerSet $containers $false
    $script:Stage='iam_start_container_absence'
    foreach ($service in @('iam-postgresql','iam-service')) {
        $iamArgs=Get-ComposeServiceContainerArguments $service
        $iamContainers=@(& docker @iamArgs 2>$null)
        if ($LASTEXITCODE -ne 0 -or @($iamContainers | Where-Object { $_ }).Count -gt 0) { throw 'An isolated IAM container already exists.' }
    }
    $script:Stage='iam_start_volume_absence'
    $iamVolumes=@(& docker @(Get-NamedVolumeArguments 'telemetry-clean-start-20260926-iam-data') 2>$null)
    if ($LASTEXITCODE -ne 0 -or @($iamVolumes | Where-Object { $_ -ceq 'telemetry-clean-start-20260926-iam-data' }).Count -gt 0) { throw 'An isolated IAM volume already exists.' }
    $script:Stage='iam_start_port_availability'
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,18081)
    try { $listener.Start() } catch { throw 'The fixed isolated IAM port is unavailable.' } finally { $listener.Stop() }
    $script:Stage='iam_start'
    $args=Get-IamStartArguments
    $args[2]=$script:EnvFile
    $null=& docker @args 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The isolated IAM start did not complete; do not retry automatically.' }
    $script:Stage='iam_start_readiness'
    $deadline=[Diagnostics.Stopwatch]::StartNew()
    while ($deadline.Elapsed.TotalSeconds -lt 90) {
        try { $response=Invoke-WebRequest -UseBasicParsing -Method Get -Uri 'http://127.0.0.1:18081/q/health/ready' -TimeoutSec 3 -ErrorAction Stop; if ([int]$response.StatusCode -eq 200) { Write-Host 'ISOLATED_IAM_READY: status=200; lifecycle=iam-services-only.'; return } } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'The isolated IAM service did not reach bounded readiness; no cleanup or retry was attempted.'
}

function Assert-LocalOrigin([string] $Value, [string] $Service) {
    $uri = $null
    if (-not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -cne 'http' -or $uri.Host -notin @('127.0.0.1', 'localhost') -or
            $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
        throw 'A fixed local service origin was invalid.'
    }
    return $Value.TrimEnd('/')
}

function Get-Settings {
    if (-not (Test-Path -LiteralPath $script:EnvFile -PathType Leaf)) { throw 'Repository-root .env is missing.' }
    $settings = @{}
    foreach ($key in @('COGNITO_CLIENT_ID','COGNITO_ISSUER_URL','COGNITO_USER_POOL_ID',
            'AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN')) {
        $matches = @(Get-Content -LiteralPath $script:EnvFile | Where-Object { $_ -match ('^' + [regex]::Escape($key) + '=') })
        if ($matches.Count -ne 1) { throw 'Required local development configuration is missing.' }
        $value = $matches[0].Substring($key.Length + 1).Trim().Trim('"').Trim("'")
        if ([string]::IsNullOrWhiteSpace($value) -or $value -match '(?i)(placeholder|example|changeme|issuer\.invalid|<[^>]+>)') {
            throw 'Required local development configuration is invalid.'
        }
        $settings[$key] = $value
    }
    $issuer = $null
    if (-not [uri]::TryCreate($settings.COGNITO_ISSUER_URL, [UriKind]::Absolute, [ref]$issuer) -or
            $issuer.Scheme -cne 'https' -or $issuer.Host -cne 'cognito-idp.us-east-1.amazonaws.com' -or
            $settings.COGNITO_USER_POOL_ID -notmatch '^us-east-1_[A-Za-z0-9]+$' -or
            $settings.COGNITO_ISSUER_URL.TrimEnd('/') -cne "https://cognito-idp.us-east-1.amazonaws.com/$($settings.COGNITO_USER_POOL_ID)") {
        throw 'The configured development identity settings do not match the fixed Cognito region.'
    }
    return $settings
}

function Save-State([pscustomobject] $State) {
    $temporary = "$($script:StateFile).new"
    $json = ConvertTo-Json -InputObject $State -Depth 5
    [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $script:StateFile -Force
}

function Read-State {
    if (-not (Test-Path -LiteralPath $script:StateFile -PathType Leaf)) { throw 'TCS-6 state is unavailable.' }
    try { return (Get-Content -LiteralPath $script:StateFile -Raw | ConvertFrom-Json) }
    catch { throw 'TCS-6 state is invalid.' }
}

function ConvertTo-Base64Url([byte[]] $Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

function New-RandomBase64Url([int] $ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ConvertTo-Base64Url $bytes
}

function Read-MaskedInput([string] $Prompt) {
    [Console]::Write($Prompt)
    $characters = [System.Collections.Generic.List[char]]::new()
    while ($true) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq [ConsoleKey]::Enter) { [Console]::WriteLine(); break }
        if ($key.Key -eq [ConsoleKey]::Backspace) {
            if ($characters.Count -gt 0) { $characters.RemoveAt($characters.Count - 1); [Console]::Write("`b `b") }
            continue
        }
        if (-not [char]::IsControl($key.KeyChar)) { $characters.Add($key.KeyChar); [Console]::Write('*') }
    }
    return -join $characters.ToArray()
}

function Get-AccessToken([hashtable] $Settings, [string] $Role) {
    $issuer = $Settings.COGNITO_ISSUER_URL.TrimEnd('/')
    try { $discovery = Invoke-RestMethod -Method Get -Uri "$issuer/.well-known/openid-configuration" -TimeoutSec 10 -MaximumRedirection 0 }
    catch { throw 'Cognito metadata request failed.' }
    if ($discovery.issuer -cne $issuer) { throw 'Cognito metadata issuer mismatch.' }
    $authorize = $null; $tokenEndpoint = $null
    if (-not [uri]::TryCreate([string]$discovery.authorization_endpoint, [UriKind]::Absolute, [ref]$authorize) -or
            -not [uri]::TryCreate([string]$discovery.token_endpoint, [UriKind]::Absolute, [ref]$tokenEndpoint) -or
            $authorize.Scheme -cne 'https' -or $tokenEndpoint.Scheme -cne 'https' -or
            $authorize.Host -notmatch '\.auth\.us-east-1\.amazoncognito\.com$' -or
            $authorize.AbsolutePath -cne '/oauth2/authorize' -or $tokenEndpoint.AbsolutePath -cne '/oauth2/token') {
        throw 'Cognito metadata endpoints are invalid.'
    }
    $verifier = New-RandomBase64Url 64
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $challenge = ConvertTo-Base64Url ($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier))) } finally { $sha.Dispose() }
    $state = New-RandomBase64Url 32
    $query = [ordered]@{ client_id=$Settings.COGNITO_CLIENT_ID; response_type='code'; redirect_uri=$script:Redirect
        scope='openid email profile'; state=$state; code_challenge=$challenge; code_challenge_method='S256'; prompt='login' }
    $encoded = ($query.GetEnumerator() | ForEach-Object { '{0}={1}' -f [uri]::EscapeDataString($_.Key),[uri]::EscapeDataString($_.Value) }) -join '&'
    $listener = [Net.HttpListener]::new(); $listener.Prefixes.Add($script:ListenerPrefix)
    try {
        $listener.Start()
        Write-Host ("PKCE_WAITING: complete {0} sign-in in the opened browser; no token will be displayed." -f $Role)
        Start-Process "$($authorize.AbsoluteUri)?$encoded"
        $pending = $listener.GetContextAsync()
        if (-not $pending.Wait([TimeSpan]::FromMinutes(10))) { throw 'PKCE callback timed out.' }
        $callback = $pending.Result
        $code = $callback.Request.QueryString['code']
        $valid = $callback.Request.Url.AbsolutePath -ceq '/auth/callback' -and
            $callback.Request.QueryString['state'] -ceq $state -and
            [string]::IsNullOrWhiteSpace($callback.Request.QueryString['error']) -and -not [string]::IsNullOrWhiteSpace($code)
        $callback.Response.StatusCode = if ($valid) { 200 } else { 400 }
        $callback.Response.ContentType = 'text/plain; charset=utf-8'
        $bytes = [Text.Encoding]::UTF8.GetBytes($(if ($valid) { 'Authentication complete. Return to the terminal.' } else { 'Authentication rejected.' }))
        $callback.Response.ContentLength64 = $bytes.Length; $callback.Response.OutputStream.Write($bytes,0,$bytes.Length); $callback.Response.Close()
        if (-not $valid) { throw 'PKCE callback was rejected.' }
        try { $tokens = Invoke-RestMethod -Method Post -Uri $tokenEndpoint.AbsoluteUri -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 -MaximumRedirection 0 -Body @{
                grant_type='authorization_code'; client_id=$Settings.COGNITO_CLIENT_ID; code=$code; redirect_uri=$script:Redirect; code_verifier=$verifier } }
        catch { throw 'PKCE token exchange failed.' }
    } finally { if ($listener.IsListening) { $listener.Stop() }; $listener.Close() }
    if ([string]::IsNullOrWhiteSpace($tokens.access_token)) { throw 'PKCE did not return an access token.' }
    $segments = $tokens.access_token.Split('.'); if ($segments.Count -ne 3) { throw 'Access token is malformed.' }
    $payload = $segments[1].Replace('-','+').Replace('_','/'); if ($payload.Length % 4 -eq 2) { $payload += '==' } elseif ($payload.Length % 4 -eq 3) { $payload += '=' }
    try { $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json } catch { throw 'Access token claims are invalid.' }
    $groups = @($claims.'cognito:groups')
    if ($claims.iss -cne $issuer -or $claims.client_id -cne $Settings.COGNITO_CLIENT_ID -or
            $claims.token_use -cne 'access' -or $groups -cnotcontains $Role -or $groups -contains 'ADMIN' -or
            ($Role -eq 'DOCTOR' -and $groups -contains 'PATIENT') -or ($Role -eq 'PATIENT' -and $groups -contains 'DOCTOR') -or
            [string]::IsNullOrWhiteSpace([string]$claims.sub) -or $claims.sub -notmatch '^[A-Za-z0-9_-]{1,128}$' -or
            $null -eq $claims.exp -or [long]$claims.exp -le [DateTimeOffset]::UtcNow.AddMinutes(1).ToUnixTimeSeconds()) {
        throw 'Access token role, issuer, or client did not match the requested test role.'
    }
    return @{ Token=[string]$tokens.access_token; Subject=[string]$claims.sub }
}

function Invoke-LocalApi([string] $Method, [string] $Uri, [string] $Token, [object] $Body=$null, [hashtable] $Headers=@{}) {
    $request = @{ Method=$Method; Uri=$Uri; Headers=@{ Authorization="Bearer $Token" }; TimeoutSec=15; UseBasicParsing=$true; ErrorAction='Stop' }
    foreach ($name in $Headers.Keys) { $request.Headers[$name]=$Headers[$name] }
    if ($null -ne $Body) { $request.ContentType='application/json'; $request.Body=ConvertTo-Json -InputObject $Body -Depth 12 -Compress }
    try { $response=Invoke-WebRequest @request; return @{ Status=[int]$response.StatusCode; Content=[string]$response.Content } }
    catch { $status=0; if ($null -ne $_.Exception.Response) { $status=[int]$_.Exception.Response.StatusCode }; return @{ Status=$status; Content=$null } }
}

function Invoke-ComposeRead([string] $Service, [string[]] $Command) {
    $args=@('compose','--env-file',$script:EnvFile,'-p',$script:Project,'-f',$script:ComposeFile,'exec','-T',$Service)+$Command
    $output=@(& docker @args 2>$null); if ($LASTEXITCODE -ne 0) { throw 'A bounded isolated read-only query failed.' }; return $output
}

function Get-ClinicalPatientId([string] $Subject) {
    if ($Subject -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Patient identity is not safe for the fixed local lookup.' }
    $sql="SELECT id FROM clinical_patients WHERE patient_cognito_sub='$Subject' AND status='ACTIVE';"
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        $rows=@(Invoke-ComposeRead 'clinical-postgresql' @('timeout','5s','psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-U','neurovision_clinical','-d','neurovision_clinical','-c',$sql) | Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
        if ($rows.Count -eq 1) { $id=[guid]::Empty; if ([guid]::TryParse($rows[0],[ref]$id) -and $id -ne [guid]::Empty) { return $id.ToString() } }
        if ($rows.Count -gt 1) { throw 'Clinical patient lookup was ambiguous.' }
        Start-Sleep -Seconds 2
    }
    throw 'Clinical registration did not become visible within the bounded wait.'
}

function Assert-Ready([string] $Origin) {
    try { $response=Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$Origin/q/health/ready" -TimeoutSec 5 -ErrorAction Stop; if ([int]$response.StatusCode -ne 200) { throw 'not ready' } }
    catch { throw 'A fixed local service was not ready.' }
}

function Invoke-DoctorSetup {
    $script:Stage='doctor_setup'
    if (Test-Path -LiteralPath $script:StateFile) { throw 'A prior TCS-6 state exists; refusing to repeat invitation.' }
    $settings=Get-Settings
    Assert-Ready 'http://127.0.0.1:18081'; Assert-Ready 'http://127.0.0.1:18083'; Assert-Ready 'http://127.0.0.1:18084'
    $identity=Get-AccessToken $settings 'DOCTOR'
    $email=Read-MaskedInput 'Enter the test patient email (input is masked and not stored): '
    if ($email -notmatch '^[^\s@]{1,64}@[^\s@.]{1,190}(\.[^\s@.]{1,63})+$' -or $email.Length -gt 254) { throw 'Patient email input is invalid.' }
    # Persist a one-shot marker before the remote provisioning request. An ambiguous outcome is never retried here.
    $state=[pscustomobject]@{ invitationAttempted=$true; invitationCompleted=$false; sessionAttempted=$false; sessionCreated=$false; ingestionAttempted=$false; ingestionAccepted=$false }
    Save-State $state
    $invite=Invoke-LocalApi 'POST' 'http://127.0.0.1:18081/api/v1/patient-invitations' $identity.Token @{ patientEmail=$email }
    $email=$null
    if ($invite.Status -ne 201) { throw 'Patient invitation did not return HTTP 201; do not retry automatically.' }
    try { $invitation=ConvertFrom-Json -InputObject $invite.Content } catch { throw 'Patient invitation response was invalid; do not retry automatically.' }
    if ($invitation.patientSubject -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw 'Patient invitation response did not contain a safe subject.' }
    $clinicalPatientId=Get-ClinicalPatientId ([string]$invitation.patientSubject)
    $clinical='http://127.0.0.1:18083'
    $planBody=@{ dailySessionLimit=3; dailyTargetDurationMinutes=30; dailyHardSafetyLimitMinutes=60; minimumValidDurationMinutes=1; maximumSessionDurationMinutes=15; minimumValidSampleCount=1; dailyExercisePrescriptions=@{ fixation=1 } }
    $planResult=Invoke-LocalApi 'POST' "$clinical/api/v1/clinical-patients/$clinicalPatientId/therapy-plans" $identity.Token $planBody
    if ($planResult.Status -ne 201) { throw 'Clinical plan creation failed; invitation is not retried.' }
    try { $plan=ConvertFrom-Json -InputObject $planResult.Content } catch { throw 'Clinical plan response was invalid.' }
    $effective=[DateTimeOffset]::UtcNow.AddSeconds(15).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $configBody=@{ exerciseCode='fixation'; parameterSchemaVersion=1; effectiveFrom=$effective; parameters=@{ mode='SUSTAINED_TARGET_FIXATION'; targetPosition='CENTER'; minimumFixationMs=1000; maximumFixationMs=3000; targetSizePx=70; repetitions=10; restBetweenRepetitionsMs=1000; difficultyLevel=1 } }
    $config=Invoke-LocalApi 'POST' "$clinical/api/v1/clinical-patients/$clinicalPatientId/therapy-plans/$($plan.therapyPlanId)/configurations" $identity.Token $configBody
    $identity.Token=$null
    if ($config.Status -ne 201) { throw 'Clinical configuration append failed; invitation and plan are not retried.' }
    $state.invitationCompleted=$true; $state.patientSubject=[string]$invitation.patientSubject; $state.clinicalPatientId=[string]$clinicalPatientId; $state.planId=[string]$plan.therapyPlanId; $state.configurationEffectiveFrom=$effective
    Save-State $state
    Write-Host 'DOCTOR_SETUP_COMPLETE: invitation=created; plan=created; configuration=appended; identifiers=withheld.'
}

function Invoke-PatientSession {
    $script:Stage='patient_session'
    $state=Read-State; Assert-StageState 'PatientSession' $state
    $settings=Get-Settings; Assert-Ready 'http://127.0.0.1:18083'
    $identity=Get-AccessToken $settings 'PATIENT'
    if ($identity.Subject -cne $state.patientSubject) { $identity.Token=$null; throw 'Patient login does not match the invited test identity.' }
    $plansResponse=Invoke-LocalApi 'GET' 'http://127.0.0.1:18083/api/v1/me/therapy-plans' $identity.Token
    if ($plansResponse.Status -ne 200) { throw 'Clinical plan query failed.' }
    try { $plans=@(ConvertFrom-Json -InputObject $plansResponse.Content) } catch { throw 'Clinical plan response was invalid.' }
    $matching=@($plans | Where-Object { $_.therapyPlanId -ceq $state.planId -and $_.status -ceq 'ACTIVE' -and $_.dailyExercisePrescriptions.FIXATION -ge 1 })
    if ($matching.Count -ne 1) { throw 'The single expected active plan is not visible to the patient.' }
    $effective=[DateTimeOffset]::Parse([string]$state.configurationEffectiveFrom).UtcDateTime
    if ([DateTime]::UtcNow -lt $effective) { throw 'Configuration is not effective yet; retrying this read-only stage is safe.' }
    $state.sessionAttempted=$true; Save-State $state
    $session=Invoke-LocalApi 'POST' "http://127.0.0.1:18083/api/v1/me/therapy-plans/$($state.planId)/sessions" $identity.Token @{ exerciseCode='FIXATION' } @{ 'Idempotency-Key'=[guid]::NewGuid().ToString() }
    $identity.Token=$null
    if ($session.Status -ne 201) { throw 'Session start did not return HTTP 201; do not retry automatically.' }
    try { $body=ConvertFrom-Json -InputObject $session.Content } catch { throw 'Session response was invalid; do not retry automatically.' }
    $sessionId=[guid]::Empty
    if (-not [guid]::TryParse([string]$body.therapySessionId,[ref]$sessionId) -or $sessionId -eq [guid]::Empty -or $body.status -cne 'ACTIVE') { throw 'Clinical did not return a new ACTIVE session.' }
    $state.sessionCreated=$true; $state.sessionId=$sessionId.ToString(); Save-State $state
    Write-Host 'PATIENT_SESSION_COMPLETE: status=201; session=ACTIVE; identifiers=withheld.'
}

function Invoke-SubmitBatch {
    $script:Stage='submit_batch'
    $state=Read-State; Assert-StageState 'SubmitBatch' $state
    $settings=Get-Settings; Assert-Ready 'http://127.0.0.1:18084'
    $identity=Get-AccessToken $settings 'PATIENT'
    if ($identity.Subject -cne $state.patientSubject) { $identity.Token=$null; throw 'Patient login does not match the invited test identity.' }
    if (-not (Test-Path -LiteralPath $script:Fixture -PathType Leaf)) { throw 'The fixed synthetic batch fixture is missing.' }
    try { $batch=Get-Content -LiteralPath $script:Fixture -Raw | ConvertFrom-Json } catch { throw 'The synthetic fixture is invalid.' }
    if ($batch.schemaVersion -ne 1 -or @($batch.gazeSamples).Count -lt 1 -or @($batch.gameplaySamples).Count -lt 1) { throw 'The synthetic fixture is not a dual-stream V1 batch.' }
    $batch.batchId=[guid]::NewGuid().ToString(); $batch.therapySessionId=$state.sessionId; $batch.streamId=[guid]::NewGuid().ToString(); $batch.sequence=0
    $batch.capturedAt=[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'); $batch.sentAt=[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $state.ingestionAttempted=$true; $state.batchId=[string]$batch.batchId; $state.expectedGazeSamples=@($batch.gazeSamples).Count; $state.expectedGameplaySamples=@($batch.gameplaySamples).Count; Save-State $state
    $response=Invoke-LocalApi 'POST' 'http://127.0.0.1:18084/api/v1/telemetry/batches' $identity.Token $batch
    $identity.Token=$null
    if ($response.Status -ne 202) { throw 'The one Telemetry batch did not return HTTP 202; no retry is permitted.' }
    $state.ingestionAccepted=$true; Save-State $state
    Write-Host ('BATCH_ACCEPTED: status=202; gazeSamples={0}; gameplaySamples={1}; batch=one; identifiers=withheld.' -f $state.expectedGazeSamples,$state.expectedGameplaySamples)
}

function Get-BatchAggregate([string] $BatchId) {
    $id=[guid]::Empty; if (-not [guid]::TryParse($BatchId,[ref]$id) -or $id -eq [guid]::Empty) { throw 'TCS-6 batch state is invalid.' }
    $sql="SELECT (SELECT count(*) FROM telemetry_batch_inbox WHERE batch_id='$($id.ToString())'), (SELECT count(*) FROM telemetry_samples WHERE batch_id='$($id.ToString())' AND sample_kind='GAZE'), (SELECT count(*) FROM telemetry_samples WHERE batch_id='$($id.ToString())' AND sample_kind='GAMEPLAY');"
    $rows=@(Invoke-ComposeRead 'telemetry-timescaledb' @('timeout','10s','psql','-X','-q','-A','-t','-F','|','-v','ON_ERROR_STOP=1','-U','neurovision_telemetry','-d','neurovision_telemetry','-c',$sql))
    return ConvertFrom-BatchAggregateOutput $rows
}

function ConvertFrom-BatchAggregateOutput([string[]] $Output) {
    $rows=@($Output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^\d+\|\d+\|\d+$' })
    if ($rows.Count -ne 1) { throw 'The bounded batch aggregate was unavailable or ambiguous.' }
    $parts=$rows[0].Split('|')
    return @{ Inbox=[int]$parts[0]; Gaze=[int]$parts[1]; Gameplay=[int]$parts[2] }
}

function Test-ExpectedBatchAggregate([hashtable] $Actual, [int] $Gaze, [int] $Gameplay) {
    return ($Actual.Inbox -eq 1 -and $Actual.Gaze -eq $Gaze -and $Actual.Gameplay -eq $Gameplay)
}

function Invoke-Verify {
    $script:Stage='verify'
    $state=Read-State; Assert-StageState 'Verify' $state
    $expected=@{ Inbox=1; Gaze=[int]$state.expectedGazeSamples; Gameplay=[int]$state.expectedGameplaySamples }
    $watch=[Diagnostics.Stopwatch]::StartNew(); $actual=$null
    while ($watch.Elapsed.TotalSeconds -lt 45) {
        $actual=Get-BatchAggregate ([string]$state.batchId)
        if (Test-ExpectedBatchAggregate $actual $expected.Gaze $expected.Gameplay) { break }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $actual -or -not (Test-ExpectedBatchAggregate $actual $expected.Gaze $expected.Gameplay)) {
        throw 'The batch-scoped persisted aggregate did not match the one accepted batch.'
    }
    Write-Host ('PERSISTENCE_VERIFIED: inboxClaims={0}; gazeSamples={1}; gameplaySamples={2}; rows=withheld.' -f $actual.Inbox,$actual.Gaze,$actual.Gameplay)
}

function Invoke-OfflineTests {
    $valid=[pscustomobject]@{ invitationCompleted=$true; sessionAttempted=$false; sessionCreated=$false; ingestionAttempted=$false; ingestionAccepted=$false }
    Assert-StageState 'PatientSession' $valid
    Assert-Throws { Assert-StageState 'SubmitBatch' $valid } 'batch-before-session-refused'
    $used=$valid.PSObject.Copy(); $used.sessionAttempted=$true
    Assert-Throws { Assert-StageState 'PatientSession' $used } 'session-retry-refused'
    $session=$valid.PSObject.Copy(); $session.sessionCreated=$true
    Assert-StageState 'SubmitBatch' $session
    $attempt=$session.PSObject.Copy(); $attempt.ingestionAttempted=$true
    Assert-Throws { Assert-StageState 'SubmitBatch' $attempt } 'batch-retry-refused'
    Assert-Throws { Assert-StageState 'Verify' $attempt } 'verify-before-acceptance-refused'
    $accepted=$attempt.PSObject.Copy(); $accepted.ingestionAccepted=$true
    Assert-StageState 'Verify' $accepted
    Assert-Throws { Assert-LocalOrigin 'https://example.test' 'Telemetry' } 'remote-origin-refused'
    Assert-Throws { Assert-LocalOrigin 'http://localhost:18084/path' 'Telemetry' } 'non-origin-refused'
    Assert-Throws { Assert-StageState 'DoctorSetup' $null } 'missing-state-refused'
    $aggregate=ConvertFrom-BatchAggregateOutput @('1|2|1')
    Assert-OfflineCase (Test-ExpectedBatchAggregate $aggregate 2 1) 'exact-one-inbox-and-kind-counts-accepted'
    Assert-OfflineCase (-not (Test-ExpectedBatchAggregate (ConvertFrom-BatchAggregateOutput @('2|2|1')) 2 1)) 'duplicate-claim-refused'
    Assert-Throws { ConvertFrom-BatchAggregateOutput @('1|2|1','1|2|1') } 'ambiguous-aggregate-refused'
    Assert-Throws { ConvertFrom-BatchAggregateOutput @('payload|1|1') } 'non-aggregate-output-refused'
    Assert-Throws { Assert-IamStartAuthorization $false $true } 'iam-start-requires-authorization'
    Assert-IamStartAuthorization $true $true
    Assert-Throws { Assert-CoreResumeAuthorization $false $true } 'core-resume-requires-authorization'
    Assert-CoreResumeAuthorization $true $true
    $networkJson='[{"Name":"telemetry-clean-start-20260926-network","Labels":{"com.docker.compose.project":"telemetry-clean-start-20260926"}}]'
    $networkArgs=Get-NetworkInspectArguments $script:NetworkName
    Assert-OfflineCase (($networkArgs -join '|') -ceq 'network|inspect|telemetry-clean-start-20260926-network') 'network-inspect-json-command-shape'
    Assert-OfflineCase (Test-NetworkIdentityDocument $networkJson $script:NetworkName $script:Project) 'network-json-project-identity-accepted'
    Assert-OfflineCase (-not (Test-NetworkIdentityDocument ($networkJson -replace 'telemetry-clean-start-20260926"','wrong-project"') $script:NetworkName $script:Project)) 'network-json-project-mismatch-refused'
    Assert-OfflineCase (-not (Test-NetworkIdentityDocument 'not-json' $script:NetworkName $script:Project)) 'network-json-malformed-refused'
    Assert-OfflineCase (-not (Test-NetworkIdentityDocument '' $script:NetworkName $script:Project)) 'network-json-empty-refused'
    $volumeJson='[{"Name":"telemetry-clean-start-20260926-kafka-data","Labels":{"com.docker.compose.project":"telemetry-clean-start-20260926","com.docker.compose.volume":"kafka-data"}}]'
    Assert-OfflineCase (Test-VolumeIdentityDocument $volumeJson 'telemetry-clean-start-20260926-kafka-data' $script:Project 'kafka-data') 'volume-json-identity-accepted'
    Assert-OfflineCase (-not (Test-VolumeIdentityDocument ($volumeJson -replace 'kafka-data','wrong') 'telemetry-clean-start-20260926-kafka-data' $script:Project 'kafka-data')) 'volume-json-mismatch-refused'
    Assert-OfflineCase (-not (Test-VolumeIdentityDocument '[]' 'telemetry-clean-start-20260926-kafka-data' $script:Project 'kafka-data')) 'empty-volume-inspection-refused'
    Assert-OfflineCase (-not (Test-VolumeIdentityDocument '' 'telemetry-clean-start-20260926-kafka-data' $script:Project 'kafka-data')) 'empty-volume-json-refused'
    Assert-OfflineCase (-not (Test-VolumeIdentityDocument 'not-json' 'telemetry-clean-start-20260926-kafka-data' $script:Project 'kafka-data')) 'malformed-volume-json-refused'
    $containerQuery=Get-ProjectContainerArguments
    $projectVolumeQuery=Get-ProjectVolumeArguments
    $namedVolumeQuery=Get-NamedVolumeArguments 'telemetry-clean-start-20260926-iam-data'
    Assert-OfflineCase (($containerQuery -join '|') -ceq "ps|--all|--filter|label=com.docker.compose.project=$($script:Project)|--quiet") 'existing-services-use-fixed-json-inspection-query'
    Assert-OfflineCase (($projectVolumeQuery -join '|') -ceq "volume|ls|--filter|label=com.docker.compose.project=$($script:Project)|--quiet") 'project-volumes-use-template-free-query'
    Assert-OfflineCase (($namedVolumeQuery -join '|') -ceq 'volume|ls|--filter|name=^telemetry-clean-start-20260926-iam-data$|--quiet') 'iam-volume-absence-uses-template-free-query'
    Assert-OfflineCase (@($containerQuery + $projectVolumeQuery + $namedVolumeQuery | Where-Object { $_ -eq '--format' -or $_ -match '\{\{' }).Count -eq 0) 'resource-query-plans-have-no-go-templates'
    $expectedNames=@('telemetry-clean-start-20260926-clinical-data','telemetry-clean-start-20260926-telemetry-data','telemetry-clean-start-20260926-kafka-data')
    Assert-OfflineCase (Test-ExactResourceNames $expectedNames $expectedNames) 'exact-volume-set-count-and-names-accepted'
    Assert-OfflineCase (-not (Test-ExactResourceNames @($expectedNames | Select-Object -First 2) $expectedNames)) 'volume-count-mismatch-refused'
    Assert-OfflineCase (-not (Test-ExactResourceNames @($expectedNames + $expectedNames[0]) $expectedNames)) 'duplicate-volume-name-refused'
    $iamQuery=Get-ComposeServiceContainerArguments 'iam-postgresql'
    Assert-OfflineCase (($iamQuery -join '|') -ceq "ps|--all|--filter|label=com.docker.compose.project=$($script:Project)|--filter|label=com.docker.compose.service=iam-postgresql|--quiet") 'iam-db-absence-query-fixed'
    $stoppedCore=New-FakeCoreContainers $false
    Assert-CoreContainerSet $stoppedCore $true
    Assert-Throws { Assert-CoreContainerSet @($stoppedCore | Select-Object -Skip 1) $true } 'core-container-count-mismatch-refused'
    $badInitExit=New-FakeCoreContainers $false
    ($badInitExit | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'kafka-init' }).State.ExitCode=9
    Assert-Throws { Assert-CoreContainerSet $badInitExit $true } 'core-resume-requires-existing-init-exit-zero'
    $dependencyCore=New-FakeCoreDependencyContainers
    Assert-CoreDependencyPhase $dependencyCore
    ($dependencyCore | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'kafka-init' }).State.Status='running'
    Assert-Throws { Assert-CoreDependencyPhase $dependencyCore } 'dependency-stage-preserves-init-exited'
    $runningCore=New-FakeCoreContainers $true
    Assert-CoreContainerSet $runningCore $false
    $coreJson=ConvertTo-Json -InputObject @($runningCore) -Depth 10 -Compress
    $parsedCore=ConvertFrom-DockerJsonArray $coreJson 'fixed test error'
    Assert-OfflineCase ($parsedCore.Count -eq 6) 'docker-inspect-json-array-count-preserved'
    Assert-OfflineCase (Test-IamStartCoreStateJson $coreJson) 'iam-start-extracts-exact-service-labels-and-states'
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson '')) 'empty-container-inspection-refused'
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson 'not-json')) 'malformed-container-inspection-refused'
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson '[]')) 'empty-container-array-refused'
    $safeParseFailure=''
    try { $null=ConvertFrom-DockerJsonArray '{"secret":"PRIVATE_MARKER"' 'fixed-safe-parse-error' } catch { $safeParseFailure=$_.Exception.Message }
    Assert-OfflineCase ($safeParseFailure -ceq 'fixed-safe-parse-error' -and $safeParseFailure -notmatch 'PRIVATE_MARKER') 'json-parse-failure-is-fixed-and-redacted'
    $shortCoreJson=ConvertTo-Json -InputObject @($runningCore | Select-Object -First 5) -Depth 10 -Compress
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson $shortCoreJson)) 'container-count-mismatch-refused'
    $badLabelCore=@($runningCore | ForEach-Object { $_ })
    $badLabelCore[0].Config.Labels.'com.docker.compose.project'='other-project'
    $badLabelJson=ConvertTo-Json -InputObject $badLabelCore -Depth 10 -Compress
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson $badLabelJson)) 'container-project-label-mismatch-refused'
    $badStateCore=New-FakeCoreContainers $true
    ($badStateCore | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'kafka-init' }).State.ExitCode=8
    $badStateJson=ConvertTo-Json -InputObject @($badStateCore) -Depth 10 -Compress
    Assert-OfflineCase (-not (Test-IamStartCoreStateJson $badStateJson)) 'container-state-mismatch-refused'
    Assert-Throws { Assert-CoreContainerSet $stoppedCore $false } 'core-health-state-mismatch-refused'
    $corePlan=Get-CoreResumeCommandPlan
    Assert-OfflineCase ((@($corePlan | ForEach-Object { $_.Stage }) -join '|') -ceq 'dependencies|applications') 'core-resume-command-stage-order'
    $dependencyArgs=@($corePlan[0].Arguments)
    $applicationArgs=@($corePlan[1].Arguments)
    Assert-OfflineCase (($dependencyArgs[-3..-1] -join '|') -ceq 'clinical-postgresql|telemetry-timescaledb|kafka') 'dependency-start-exact-service-set'
    Assert-OfflineCase (($applicationArgs[-2..-1] -join '|') -ceq 'clinical-service|telemetry-service') 'application-start-exact-service-set'
    Assert-OfflineCase ((($dependencyArgs + $applicationArgs) -join '|') -notmatch '(^|\|)(up|kafka-init|iam-service|iam-postgresql|--build|--pull|--wait)(\||$)') 'staged-start-never-creates-or-restarts-init-iam'
    $coreGuards=@{
        core_resume_authorization='core_resume_authorization_failed'
        core_resume_compose_config='core_resume_compose_config_validation_failed'
        core_resume_network_identity='core_resume_network_identity_failed'
        core_resume_volume_set='core_resume_volume_set_failed'
        core_resume_volume_clinical_postgresql_data='core_resume_volume_clinical_postgresql_failed'
        core_resume_volume_telemetry_timescaledb_data='core_resume_volume_telemetry_timescaledb_failed'
        core_resume_volume_kafka_data='core_resume_volume_kafka_failed'
        core_resume_iam_absence='core_resume_iam_absence_check_failed'
        core_resume_containers='core_resume_container_inspection_failed'
        core_resume_stopped_state='core_resume_stopped_state_mismatch'
        core_resume_port_18083='core_resume_port_18083_unavailable'
        core_resume_port_18084='core_resume_port_18084_unavailable'
        core_resume_port_19092='core_resume_port_19092_unavailable'
        core_resume_dependency_start='core_resume_dependency_start_failed'
        core_resume_dependency_health='core_resume_dependency_health_failed'
        core_resume_application_start='core_resume_application_start_failed'
        core_resume_application_state='core_resume_application_state_failed'
        core_resume_readiness='core_resume_readiness_failed'
    }
    foreach ($guard in $coreGuards.Keys) {
        Assert-OfflineCase ((Get-CoreResumeDiagnosticCode $guard) -ceq $coreGuards[$guard]) ('core-diagnostic-map-' + $guard)
    }
    Assert-OfflineCase ((Format-CoreResumeDiagnostic 'core_resume_stopped_state' 'PRIVATE_ENV_VALUE') -notmatch 'PRIVATE_ENV_VALUE') 'core-resume-diagnostic-redacts-exception'
    $iamGuards=@{
        iam_start_authorization='iam_start_authorization_failed'
        iam_start_settings='iam_start_settings_validation_failed'
        iam_start_compose_config='iam_start_compose_config_validation_failed'
        iam_start_network_identity='iam_start_network_identity_failed'
        iam_start_existing_services='iam_start_existing_services_inspection_failed'
        iam_start_container_absence='iam_start_container_absence_check_failed'
        iam_start_volume_absence='iam_start_volume_absence_check_failed'
        iam_start_port_availability='iam_start_port_availability_failed'
        iam_start='iam_start_operation_failed'
        iam_start_readiness='iam_start_readiness_failed'
    }
    foreach ($guard in $iamGuards.Keys) {
        Assert-OfflineCase ((Get-IamStartDiagnosticCode $guard) -ceq $iamGuards[$guard]) ('diagnostic-map-' + $guard)
    }
    $safeDiagnostic=Format-IamStartDiagnostic 'iam_start_settings' 'PRIVATE_ENV_VALUE'
    Assert-OfflineCase ($safeDiagnostic -ceq 'TCS-6 stopped safely; diagnostic=iam_start_settings_validation_failed; no automatic replay or cleanup was attempted.' -and $safeDiagnostic -notmatch 'PRIVATE_ENV_VALUE') 'diagnostic-redacts-exception-text'
    Assert-OfflineCase ((Get-IamStartDiagnosticCode 'untrusted-private-value') -ceq 'iam_start_guard_failed') 'unknown-diagnostic-is-fixed'
    Assert-OfflineCase ((Format-IamStartDiagnostic 'iam_start' 'PRIVATE_ENV_VALUE') -ceq 'TCS-6 stopped safely; diagnostic=iam_start_operation_failed; no automatic replay or cleanup was attempted.') 'iam-start-operation-stage-is-classified'
    Assert-OfflineCase ((Format-IamStartDiagnostic 'iam_start_readiness' 'PRIVATE_ENV_VALUE') -ceq 'TCS-6 stopped safely; diagnostic=iam_start_readiness_failed; no automatic replay or cleanup was attempted.') 'iam-start-readiness-stage-is-classified'
    $iamArgs=Get-IamStartArguments
    Assert-OfflineCase (($iamArgs -join '|') -match 'up\|-d\|--no-build\|--pull\|never\|iam-postgresql\|iam-service$') 'iam-start-is-scoped-no-build-no-pull'
    Write-Output 'TCS6_OFFLINE_TESTS_PASSED: one-way-stage-guards=6; loopback-guards=2; missing-state=1; iam-start-authorization=2; iam-start-diagnostics=12; core-resume-authorization=2; network-json-guards=5; volume-json-guards=5; container-json-guards=9; resource-query-shapes=4; volume-set-regressions=3; one-shot-preservation=3; staged-command-order=4; core-resume-diagnostics=19; aggregate-fakes=4; api-calls=0; docker-lifecycle-calls=0; database-calls=0'
}

function Assert-Throws([scriptblock] $Action, [string] $Name) {
    $failed=$false; try { & $Action } catch { $failed=$true }
    if (-not $failed) { throw "Offline TCS-6 guard test failed: $Name" }
}

function Assert-OfflineCase([bool] $Condition, [string] $Name) {
    if (-not $Condition) { throw "Offline TCS-6 guard test failed: $Name" }
}

try {
    if ($Mode -notin @('ResumeIsolatedCore','StartIsolatedIam') -and
            ($AuthorizeIsolatedCoreResume -or $AuthorizeIsolatedIamStart -or $ConfirmDisposableSyntheticEnvironment)) {
        throw 'Runtime authorization switches are valid only with their separately gated lifecycle mode.'
    }
    if (($Mode -eq 'ResumeIsolatedCore' -and $AuthorizeIsolatedIamStart) -or
            ($Mode -eq 'StartIsolatedIam' -and $AuthorizeIsolatedCoreResume)) {
        throw 'A lifecycle authorization switch does not match the selected mode.'
    }
    switch ($Mode) {
        'OfflineTest' { Invoke-OfflineTests }
        'ResumeIsolatedCore' { Invoke-CoreResume }
        'StartIsolatedIam' { Invoke-IamStart }
        'DoctorSetup' { Invoke-DoctorSetup }
        'PatientSession' { Invoke-PatientSession }
        'SubmitBatch' { Invoke-SubmitBatch }
        'Verify' { Invoke-Verify }
    }
} catch {
    $safeStage=$script:Stage -replace '[^a-z0-9_]', '_'
    if ($Mode -eq 'OfflineTest') { throw }
    if ($Mode -eq 'ResumeIsolatedCore') {
        Write-Error -Message (Format-CoreResumeDiagnostic $safeStage $_.Exception.Message) -ErrorAction Continue
        exit 1
    }
    if ($Mode -eq 'StartIsolatedIam') {
        Write-Error -Message (Format-IamStartDiagnostic $safeStage $_.Exception.Message) -ErrorAction Continue
        exit 1
    }
    Write-Error -Message ("TCS-6 stopped safely; stage={0}; no automatic replay or cleanup was attempted." -f $safeStage) -ErrorAction Continue
    exit 1
}
