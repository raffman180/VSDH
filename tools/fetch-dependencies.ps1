$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$dependencyRoot = Join-Path $projectRoot 'libs'
New-Item -ItemType Directory -Force -Path $dependencyRoot | Out-Null
$dependencies = @(
    @{
        Name = 'flywheel-neoforge-1.21.1-1.0.6.jar'
        Url = 'https://maven.createmod.net/dev/engine-room/flywheel/flywheel-neoforge-1.21.1/1.0.6/flywheel-neoforge-1.21.1-1.0.6.jar'
        Algorithm = 'SHA256'
        Hash = '31dda15c205eb596d3b3449ef03f6af7363a6cd35b3da4bfe916b304f9e5337e'
    },
    @{
        Name = 'DistantHorizons-3.3.1-1.21.1-fabric-neoforge.jar'
        Url = 'https://cdn.modrinth.com/data/uCdwusMi/versions/IcOcoekl/DistantHorizons-3.3.1-1.21.1-fabric-neoforge.jar'
        Algorithm = 'SHA512'
        Hash = 'd64daa41c419410421695afbbc72da47372168ed8346f5154fb9232b401f86e4c3bf8b4e8a29deb36a70af98fb804ce2b9041334f0931cea3f6b7156c7668c1d'
    },
    @{
        Name = 'DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar'
        Url = 'https://cdn.modrinth.com/data/uCdwusMi/versions/9w34y8ai/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar'
        Algorithm = 'SHA512'
        Hash = 'd14b32d3fd143a06514d6ad388eee11e9d657c84e1a937aaf8d033b1137fb43ef8b05cff1011a994cc42f3106823c51234f8bc18d72bb05bc317a20ea83c50ed'
    }
)
foreach ($dependency in $dependencies) {
    $targetPath = Join-Path $dependencyRoot $dependency.Name
    if (Test-Path -LiteralPath $targetPath) {
        $actualHash = (Get-FileHash -LiteralPath $targetPath -Algorithm $dependency.Algorithm).Hash
        if ($actualHash -ne $dependency.Hash) { throw "Existing dependency has wrong hash: $targetPath" }
        Write-Output "Verified: $($dependency.Name)"
        continue
    }
    $downloadPath = $targetPath + '.download'
    Invoke-WebRequest -UseBasicParsing -Uri $dependency.Url -OutFile $downloadPath -TimeoutSec 60
    $actualHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm $dependency.Algorithm).Hash
    if ($actualHash -ne $dependency.Hash) { throw "Downloaded dependency has wrong hash: $downloadPath" }
    Move-Item -LiteralPath $downloadPath -Destination $targetPath
    Write-Output "Downloaded and verified: $($dependency.Name)"
}
