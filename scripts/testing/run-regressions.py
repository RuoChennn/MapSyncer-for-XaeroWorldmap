"""Compile focused production classes and run deterministic filesystem regressions."""
from pathlib import Path
import os
import subprocess
import sys
import shutil

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'cache/tmp/sync-feature-regressions'
WORK.mkdir(parents=True, exist_ok=True)
os.environ.update(TEMP=str(WORK), TMP=str(WORK), TMPDIR=str(WORK))
JAVA_HOME = os.environ.get('JAVA_HOME')
JAVAC = str(Path(JAVA_HOME) / 'bin/javac') if JAVA_HOME else 'javac'
JAVA = str(Path(JAVA_HOME) / 'bin/java') if JAVA_HOME else 'java'
gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
logging = list(gradle_home.glob('caches/modules-2/files-2.1/org.slf4j/slf4j-api/2.0.9/*/*.jar'))
if not logging:
    sys.exit('Build a target first: existing slf4j-api 2.0.9 cache required')
logging_copy = WORK / 'slf4j-api.jar'
shutil.copyfile(logging[0], logging_copy)
version = next(line.split('=', 1)[1].strip() for line in (ROOT / 'gradle.properties').read_text().splitlines()
               if line.startswith('mod_version='))
artifact = ROOT / 'output' / f'mapsyncer-{version}-fabric-1.21.jar'
if not artifact.is_file():
    sys.exit('Run build-target.bat fabric-1.21.1 first')
classes = WORK / 'classes'
classes.mkdir(exist_ok=True)
classpath = os.pathsep.join(map(str, [classes, artifact, logging_copy]))
sources = [
    'libs/common/src/main/java/com/mapsyncer/server/SyncRadiusPolicy.java',
    'libs/common/src/main/java/com/mapsyncer/server/McaTimestampCache.java',
    'libs/common/src/main/java/com/mapsyncer/util/DimensionPathMapping.java',
    'libs/common/src/main/java/com/mapsyncer/client/XaeroMapDataHandler.java',
    'libs/platform-api/src/main/java/com/mapsyncer/network/payload/SyncRequestPayload.java',
    'tests/regression/SyncFeatureRegression.java',
]
subprocess.run([JAVAC, '--release', '17', '-encoding', 'UTF-8', '-cp', classpath,
                '-d', str(classes), *[str(ROOT / source) for source in sources]], check=True, cwd=ROOT)
fixtures = WORK / 'fixtures'
if fixtures.exists():
    assert fixtures.resolve().is_relative_to((ROOT / 'cache').resolve())
    shutil.rmtree(fixtures)
subprocess.run([JAVA, '-Djava.io.tmpdir=' + str(WORK), '-cp', classpath,
                'SyncFeatureRegression', str(fixtures)], check=True, cwd=ROOT)
