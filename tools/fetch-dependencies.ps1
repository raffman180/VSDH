$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$dependencyRoot = Join-Path $projectRoot 'libs'
New-Item -ItemType Directory -Force -Path $dependencyRoot | Out-Null
$dependencies = @(
    @{
        Name = 'VulkanMod-Reforged-0.3.5-ALPHA+1.21.1.jar'
        Url = 'https://github.com/TrulyRin/VulkanMod-Reforged/releases/download/mc-1.21.1-0618965/VulkanMod-Reforged-0.3.5-ALPHA%2B1.21.1.jar'
        Algorithm = 'SHA256'
        Hash = '61a1c215f4dd591776fc890a7623680ebf409f7ce8c4d083eab0f18b07ec50f1'
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
