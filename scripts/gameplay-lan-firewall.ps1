param([switch]$Remove)
$ErrorActionPreference='Stop'
$ruleName='AnimeOnCraft-Gameplay-LAN-Review'
if($Remove){Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule;return}
$address='192.168.1.161'
$interface=Get-NetIPAddress -AddressFamily IPv4 -IPAddress $address -ErrorAction Stop
if($interface.PrefixLength -ne 24){throw 'Network changed; update LAN review configuration before opening access.'}
Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name $ruleName -DisplayName 'AnimeOnCraft temporary LAN gameplay review' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3000,25565 -LocalAddress $address -RemoteAddress '192.168.1.0/24' -InterfaceAlias $interface.InterfaceAlias -Profile Any | Out-Null
Write-Host 'Temporary access opened only from 192.168.1.0/24 to this Ethernet IP. Remove after review with -Remove.'
