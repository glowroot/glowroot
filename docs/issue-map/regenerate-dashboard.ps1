#Requires -Version 5.1
<#
.SYNOPSIS
  Rebuild docs/issue-map/data.json + index.html (optional GitHub fetch).

.EXAMPLE
  .\regenerate-dashboard.ps1 -AddIds 631,633
  .\regenerate-dashboard.ps1 -Fetch -OpenBrowser
#>
[CmdletBinding()]
param(
  # Prefer: -AddIds 631,633   or   -AddIdsCsv '631,633'
  [int[]] $AddIds = @(),
  [string] $AddIdsCsv = '',
  [switch] $Fetch,
  [switch] $OpenBrowser,
  [switch] $SkipCsv
)

if ($AddIdsCsv) {
  $parsed = @($AddIdsCsv.Split(@(',', ' ', ';'), [StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object { [int]$_.Trim() })
  $AddIds = @($AddIds) + $parsed
}

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }
Set-Location $here

function Get-Difficulty([string] $title) {
  $t = $title.ToLowerInvariant()
  $easy = @(
    'how to','how can','how do','can i','can we','is it possible',"it's possible",
    'question','install','installation','setenv','permission','wiki','download',
    'password','username','bind address','log4j','cve-','demo site','not starting',
    'failed to start','cannot start','unable to start','what should i','please help',
    'help me','bulk','schedule','threaddump','thread dump','embed','dist','database',
    'collector','jmx','mbean','threshold','distributed tracing','waited time','blocked time',
    'get involved','maintainer'
  )
  $hard = @(
    'weave','weaving','classcircularity','metaspace','classloader leak','oom','outofmemory',
    'memory leak','incompatibleclasschange','serialversionuid','bytecode','fastthreadlocal',
    'virtual thread','tombstone'
  )
  foreach ($k in $hard) { if ($t.Contains($k)) { return 'hard' } }
  foreach ($k in $easy) { if ($t.Contains($k)) { return 'easy' } }
  if ($t -match '\?') { return 'easy' }
  return 'medium'
}

function Map-Issue($i, $now, [string] $createdField, [string] $urlField) {
  $createdRaw = $i.$createdField
  if (-not $createdRaw) { $createdRaw = $i.created_at }
  if (-not $createdRaw) { $createdRaw = $i.createdAt }
  $created = [datetime]$createdRaw
  $age = ($now - $created).TotalDays
  $bucket = if ($age -lt 30) { 'fresh' } elseif ($age -lt 365) { 'year' } elseif ($age -lt 1095) { 'mid' } else { 'stale' }
  $user = $i.user
  if (-not $user -and $i.author -and $i.author.login) { $user = $i.author.login }
  if (-not $user) { $user = 'ghost' }
  $labels = ''
  if ($i.labels) {
    if ($i.labels[0] -is [string]) { $labels = ($i.labels -join ', ') }
    else { $labels = ($i.labels | ForEach-Object { $_.name }) -join ', ' }
  }
  $url = $i.$urlField
  if (-not $url) { $url = $i.html_url }
  if (-not $url) { $url = $i.url }
  $title = $i.title
  # gh --json comments returns comment objects; older caches may store a count
  $commentCount = 0
  if ($null -ne $i.comments) {
    if ($i.comments -is [System.Array]) { $commentCount = @($i.comments).Count }
    elseif ($i.comments -is [string] -or $i.comments -is [int] -or $i.comments -is [long] -or $i.comments -is [double]) {
      $commentCount = [int]$i.comments
    }
    else { $commentCount = @($i.comments).Count }
  }
  [ordered]@{
    number     = [int]$i.number
    created    = $created.ToString('yyyy-MM-dd')
    ageDays    = [int]$age
    user       = $user
    labels     = $labels
    title      = $title
    url        = $url
    comments   = $commentCount
    bucket     = $bucket
    difficulty = (Get-Difficulty $title)
  }
}

# --- triaged ids ---
$triagedPath = Join-Path $here 'triaged-ids.json'
$triagedObj = Get-Content $triagedPath -Raw | ConvertFrom-Json
$ids = [System.Collections.Generic.List[int]]::new()
foreach ($id in @($triagedObj.ids)) { if (-not $ids.Contains([int]$id)) { $ids.Add([int]$id) } }
foreach ($id in $AddIds) { if (-not $ids.Contains([int]$id)) { $ids.Add([int]$id) } }
$ids.Sort()
$triagedObj = [ordered]@{
  updated = (Get-Date).ToString('yyyy-MM-dd HH:mm')
  note    = 'Issues already commented in backlog triage. Excluded from work queues.'
  ids     = @($ids)
}
[System.IO.File]::WriteAllText($triagedPath, ($triagedObj | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))

# --- optional fetch ---
$ndjsonPath = Join-Path $here 'issues-ndjson.txt'
if ($Fetch) {
  Write-Host 'Fetching issues from GitHub...'
  $raw = gh issue list -R glowroot/glowroot --state all --limit 2000 --json number,title,state,createdAt,updatedAt,closedAt,author,labels,comments,url
  $fetched = $raw | ConvertFrom-Json
  $lines = foreach ($i in $fetched) {
    ([ordered]@{
      number     = $i.number
      state      = $i.state.ToLower()
      title      = $i.title
      created_at = $i.createdAt
      updated_at = $i.updatedAt
      closed_at  = $i.closedAt
      user       = if ($i.author.login) { $i.author.login } else { 'ghost' }
      labels     = @($i.labels | ForEach-Object name)
      comments   = @($i.comments).Count
      html_url   = $i.url
    } | ConvertTo-Json -Compress)
  }
  [System.IO.File]::WriteAllLines($ndjsonPath, $lines, [System.Text.UTF8Encoding]::new($false))
  Write-Host "Fetched $($fetched.Count) issues"
}

$issues = Get-Content $ndjsonPath | ForEach-Object { $_ | ConvertFrom-Json }
$now = Get-Date
$openIssues = @($issues | Where-Object { $_.state -eq 'open' })
$ts = [System.Collections.Generic.HashSet[int]]::new()
foreach ($id in $ids) { [void]$ts.Add([int]$id) }
$openWork = @($openIssues | Where-Object { -not $ts.Contains([int]$_.number) })
$openTriaged = @($openIssues | Where-Object { $ts.Contains([int]$_.number) } | Sort-Object number -Descending)

$mappedWork = @($openWork | Sort-Object number -Descending | ForEach-Object { Map-Issue $_ $now 'created_at' 'html_url' })
$byDiff = @{
  easy   = @($mappedWork | Where-Object difficulty -eq 'easy').Count
  medium = @($mappedWork | Where-Object difficulty -eq 'medium').Count
  hard   = @($mappedWork | Where-Object difficulty -eq 'hard').Count
}

if (-not $SkipCsv) {
  $csv = [System.Collections.Generic.List[string]]::new()
  [void]$csv.Add('number,created,ageDays,user,labels,title,url,comments,bucket,difficulty')
  foreach ($r in $mappedWork) {
    $title = '"' + ($r.title -replace '"', '""') + '"'
    [void]$csv.Add("$($r.number),$($r.created),$($r.ageDays),$($r.user),`"$($r.labels)`",$title,$($r.url),$($r.comments),$($r.bucket),$($r.difficulty)")
  }
  [System.IO.File]::WriteAllLines((Join-Path $here 'open-issues.csv'), $csv, [System.Text.UTF8Encoding]::new($false))
}

$payload = [ordered]@{
  generated     = $now.ToString('yyyy-MM-dd HH:mm')
  total         = $issues.Count
  open          = $openIssues.Count
  closed        = ($issues.Count - $openIssues.Count)
  openWork      = $openWork.Count
  triagedCount  = $openTriaged.Count
  triagedIds    = @($ids)
  byDifficulty  = $byDiff
  byYear        = @($issues | Group-Object { ([datetime]$_.created_at).ToString('yyyy') } | Sort-Object Name | ForEach-Object {
    [ordered]@{ year = $_.Name; created = $_.Count; stillOpen = (@($_.Group | Where-Object state -eq 'open').Count) }
  })
  byMonth24     = @($issues | Where-Object { [datetime]$_.created_at -ge $now.AddMonths(-24) } | Group-Object { ([datetime]$_.created_at).ToString('yyyy-MM') } | Sort-Object Name | ForEach-Object {
    [ordered]@{ month = $_.Name; count = $_.Count }
  })
  openAge       = @{
    d30  = @($openWork | Where-Object { ($now - [datetime]$_.created_at).TotalDays -lt 30 }).Count
    d90  = @($openWork | Where-Object { $d = ($now - [datetime]$_.created_at).TotalDays; $d -ge 30 -and $d -lt 90 }).Count
    d365 = @($openWork | Where-Object { $d = ($now - [datetime]$_.created_at).TotalDays; $d -ge 90 -and $d -lt 365 }).Count
    y3   = @($openWork | Where-Object { $d = ($now - [datetime]$_.created_at).TotalDays; $d -ge 365 -and $d -lt 1095 }).Count
    old  = @($openWork | Where-Object { ($now - [datetime]$_.created_at).TotalDays -ge 1095 }).Count
  }
  unlabeledOpen = @($openWork | Where-Object { -not $_.labels -or $_.labels.Count -eq 0 }).Count
  last12        = @($issues | Where-Object { [datetime]$_.created_at -ge $now.AddYears(-1) }).Count
  last24        = @($issues | Where-Object { [datetime]$_.created_at -ge $now.AddYears(-2) }).Count
  openRecent    = @($mappedWork | Where-Object { [datetime]$_.created -ge [datetime]'2024-01-01' })
  allOpen       = $mappedWork
  easyQueue     = @($mappedWork | Where-Object difficulty -eq 'easy' | Select-Object -First 40)
  triaged       = @($openTriaged | ForEach-Object { Map-Issue $_ $now 'created_at' 'html_url' })
}

$dataPath = Join-Path $here 'data.json'
[System.IO.File]::WriteAllText($dataPath, ($payload | ConvertTo-Json -Depth 6 -Compress), [System.Text.UTF8Encoding]::new($false))

$htmlPath = Join-Path $here 'index.html'
$html = [System.IO.File]::ReadAllText($htmlPath)
$dataSafe = ([System.IO.File]::ReadAllText($dataPath)) -replace '</script>', '<\/script>'
$idx = $html.IndexOf('const DATA =')
$end = $html.IndexOf("`ndocument.getElementById('gen')")
if ($idx -lt 0 -or $end -lt 0) { throw "DATA markers missing in index.html (const DATA = / document.getElementById gen)" }
[System.IO.File]::WriteAllText($htmlPath, ($html.Substring(0, $idx) + "const DATA =$dataSafe`n;" + $html.Substring($end)), [System.Text.UTF8Encoding]::new($false))

Write-Host "open=$($openIssues.Count) work=$($openWork.Count) triaged=$($openTriaged.Count) easy=$($byDiff.easy) medium=$($byDiff.medium) hard=$($byDiff.hard)"
Write-Host "updated: data.json + index.html (+ triaged-ids.json)"

if ($OpenBrowser) {
  Start-Process "file:///$($htmlPath.Replace('\','/'))"
}
