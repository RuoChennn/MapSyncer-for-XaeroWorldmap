"""Validate the self-contained release jars produced by the fastbuild matrix."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[2]
version = next(line.split('=', 1)[1].strip() for line in (root / 'gradle.properties').read_text().splitlines()
               if line.startswith('mod_version='))
targets = {'fabric': ['1.20', '1.21', '1.21.11', '26.1', '26.2', '26.3'],
           'forge': ['1.20', '1.21', '1.21.11'],
           'neoforge': ['1.21', '1.21.11', '26.1', '26.2', '26.3']}
required = ['com/mapsyncer/server/SyncRadiusPolicy.class', 'com/mapsyncer/server/ServerSyncHandlerLogic.class',
            'com/mapsyncer/platform/Platform.class', 'com/mapsyncer/mca/RegionConverterStandalone.class',
            'com/mapsyncer/net/jpountz/lz4/LZ4Factory.class',
            'assets/mapsyncer/lang/en_us.json', 'assets/mapsyncer/lang/zh_cn.json']
results = []
for loader, versions in targets.items():
    for mc in versions:
        path = root / 'output' / f'mapsyncer-{version}-{loader}-{mc}.jar'
        with zipfile.ZipFile(path) as jar:
            names = jar.namelist()
            missing = set(required) - set(names)
            if missing:
                raise RuntimeError(f'{path.name}: missing bundled entries {sorted(missing)}')
            if any(name.startswith('net/jpountz/') for name in names):
                raise RuntimeError(f'{path.name}: unrelocated LZ4 would conflict with C2ME')
            metadata = 'fabric.mod.json' if loader == 'fabric' else 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml'
            if metadata not in names:
                raise RuntimeError(f'{path.name}: missing {metadata}')
            for lang in ['en_us', 'zh_cn']:
                strings = json.loads(jar.read(f'assets/mapsyncer/lang/{lang}.json'))
                if 'mapsyncer.command.radius_status' not in strings:
                    raise RuntimeError(f'{path.name}: missing radius translation')
        results.append(dict(jar=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size))
output = root / 'cache/tmp/review-sync/release-jars.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
print(f'PASS: {len(results)} self-contained release jars; manifest: {output}')
