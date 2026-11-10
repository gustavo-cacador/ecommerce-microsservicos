$wrapper = Join-Path $PSScriptRoot '../../../mvnw.cmd'

& $wrapper -B -f (Join-Path $PSScriptRoot 'pom.xml') package '-Dmaven.test.skip=true'
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $wrapper -B -f (Join-Path $PSScriptRoot 'tests/pom.xml') test
exit $LASTEXITCODE
