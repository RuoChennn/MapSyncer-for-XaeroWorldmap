"""Fixed-instance client regression support. UI commands stay in the real chat.

All backups, process output, manifests and fixtures live in repository cache/.
This tool never converts missing evidence into PASS.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
CLIENT_ROOT = Path('E:/Game/pcl/.minecraft/versions')
SERVER_ROOT = Path('E:/Game/服务器端')
JDK_ROOT = Path('C:/Program Files/Eclipse Adoptium')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines()
                if '=' in line and not line.lstrip().startswith('#'))

def set_properties(path, changes):
    lines = path.read_text(encoding='utf-8').splitlines() if path.exists() else []
    result = []
    remaining = dict(changes)
    for line in lines:
        key = line.split('=', 1)[0]
        if not line.lstrip().startswith('#') and key in remaining:
            result.append(key + '=' + str(remaining.pop(key)))
        else:
            result.append(line)
    result.extend(key + '=' + str(value) for key, value in remaining.items())
    path.write_text('\n'.join(result) + '\n', encoding='utf-8')

def local_port_free(port):
    with socket.socket() as sock:
        return sock.connect_ex(('127.0.0.1', port)) != 0

def read_tail(path, offset=0):
    if not path.exists():
        return ''
    with path.open('rb') as handle:
        handle.seek(min(offset, path.stat().st_size))
        return handle.read().decode('utf-8', errors='replace')

def java(major):
    return sorted(JDK_ROOT.glob(f'jdk-{major}*/bin/java.exe'))[-1]

def launch(args, cwd, work, name, jdk=None):
    env = dict(os.environ, TEMP=str(work), TMP=str(work), TMPDIR=str(work))
    if jdk:
        env['JAVA_HOME'] = str(jdk.parent.parent)
        env['PATH'] = str(jdk.parent) + os.pathsep + env.get('PATH', '')
    with (work / f'{name}.out.log').open('wb') as out, (work / f'{name}.err.log').open('wb') as err:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=out, stderr=err, creationflags=subprocess.CREATE_NO_WINDOW)
    return process.pid

def begin(args):
    client, server, jar = Path(args.client).resolve(), Path(args.server).resolve(), Path(args.jar).resolve()
    assert client.is_relative_to(CLIENT_ROOT.resolve()) and client != CLIENT_ROOT.resolve()
    assert server.is_relative_to(SERVER_ROOT.resolve()) and server != SERVER_ROOT.resolve()
    assert jar.is_relative_to((ROOT / 'output').resolve()) and jar.is_file()
    if not local_port_free(25565):
        raise RuntimeError('25565 is already in use; do not stop an unrelated process')
    stamp = time.strftime('%Y%m%d-%H%M%S')
    work = ROOT / 'cache/tmp/client-regression' / stamp
    work.mkdir(parents=True)
    manifest = dict(client=str(client), server=str(server), jar=str(jar), sha256=sha(jar),
                    backups=[], absent=[], deployed=[], processes={}, steps={})
    for side, directory in [('client', client), ('server', server)]:
        for old in (directory / 'mods').glob('mapsyncer*.jar'):
            dest = work / 'backups' / side / 'mods' / old.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(old, dest)
            manifest['backups'].append(dict(original=str(old), backup=str(dest), moved=True))
        paths = ['config/mapsyncer-server.properties', 'config/mapsyncer-server.toml',
                 'config/mapsyncer-client.properties', 'config/mapsyncer-client.toml']
        if side == 'server':
            paths += ['server.properties', 'ops.json']
            level = properties(server / 'server.properties').get('level-name', 'world')
            world = (server / level).resolve()
            if not world.is_relative_to(server):
                raise RuntimeError('World directory must stay inside the fixed test server')
            paths.append(str(world.relative_to(server) / 'serverconfig/mapsyncer-server.toml'))
        for relative in paths:
            original = directory / relative
            if original.is_file():
                dest = work / 'backups' / side / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(original, dest)
                manifest['backups'].append(dict(original=str(original), backup=str(dest), moved=False))
            else:
                manifest['absent'].append(str(original))
    # Persist recovery data before the first external mutation.
    save(work / 'session.json', manifest)
    for backup in manifest['backups']:
        if backup['moved']:
            Path(backup['original']).unlink()
    for directory in [client, server]:
        target = directory / 'mods' / jar.name
        shutil.copy2(jar, target)
        manifest['deployed'].append(str(target))
        save(work / 'session.json', manifest)
        if sha(target) != manifest['sha256']:
            raise RuntimeError('Jar checksum mismatch; restore session')
    set_properties(server / 'server.properties', {'gamemode': 'creative', 'force-gamemode': 'true',
                   'enforce-secure-profile': 'false', 'enable-rcon': 'true',
                   'rcon.password': uuid.uuid4().hex, 'rcon.port': '25575'})
    ops = json.loads((server / 'ops.json').read_text(encoding='utf-8')) if (server / 'ops.json').exists() else []
    offline = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:CodexTester').digest(), version=3)
    ops = [entry for entry in ops if entry.get('name') != 'CodexTester']
    ops.append(dict(uuid=str(offline), name='CodexTester', level=4, bypassesPlayerLimit=False))
    save(server / 'ops.json', ops)
    save(work / 'session.json', manifest)
    print(work / 'session.json')

def start_server(manifest, work):
    directory = Path(manifest['server'])
    jar = next(directory.glob('fabric-server*.jar'), None)
    major = 17 if '1.20' in directory.name else 25 if '26.' in directory.name else 21
    jdk = java(major)
    if jar:
        command = [str(jdk), '-Xmx3G', '-Djava.io.tmpdir=' + str(work), '-jar', str(jar), 'nogui']
    elif (directory / 'run.bat').is_file():
        command = ['cmd', '/d', '/c', 'run.bat', '--nogui']
    else:
        raise RuntimeError('No installed Fabric launcher or Forge/NeoForge run.bat')
    pid = launch(command, directory, work, 'server', jdk)
    manifest['processes']['server'] = pid
    save(work / 'session.json', manifest)
    print(f'server_pid={pid}')

def start_client(manifest, work, singleplayer, username='CodexTester', role='client', address='localhost:25565'):
    directory = Path(manifest['client'])
    version = directory.name
    config = json.loads((directory / (version + '.json')).read_text(encoding='utf-8'))
    minecraft = CLIENT_ROOT.parent
    libs = minecraft / 'libraries'
    features = {'has_custom_resolution': True}
    def allowed(rules):
        if not rules:
            return True
        result = False
        for rule in rules:
            os_rule = rule.get('os', {})
            match = os_rule.get('name', 'windows') == 'windows'
            match &= all(features.get(key, False) == value for key, value in rule.get('features', {}).items())
            if match:
                result = rule['action'] == 'allow'
        return result
    classpath = []
    for lib in config['libraries']:
        if not allowed(lib.get('rules')):
            continue
        relative = lib.get('downloads', {}).get('artifact', {}).get('path')
        if not relative:
            group, artifact, ver, *classifier = lib['name'].split(':')
            suffix = '-' + classifier[0] if classifier else ''
            relative = f'{group.replace(".", "/")}/{artifact}/{ver}/{artifact}-{ver}{suffix}.jar'
        path = libs / relative
        if not path.exists():
            raise RuntimeError(f'Missing installed library: {path}')
        if str(path).lower() not in [str(item).lower() for item in classpath]:
            classpath.append(path)
    classpath.append(directory / (version + '.jar'))
    if not re.fullmatch(r'[A-Za-z0-9_]{1,16}', username) or not re.fullmatch(r'[a-z0-9-]+', role):
        raise ValueError('Invalid test username or process role')
    placeholders = {'auth_player_name': username, 'version_name': version, 'game_directory': str(directory),
                    'assets_root': str(minecraft / 'assets'), 'assets_index_name': config['assetIndex']['id'],
                    'auth_uuid': str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + username).encode()).digest(), version=3)).replace('-', ''),
                    'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy',
                    'version_type': 'release', 'resolution_width': '1280', 'resolution_height': '720',
                    'natives_directory': str(directory / (version + '-natives')), 'library_directory': str(libs),
                    'classpath_separator': os.pathsep, 'classpath': os.pathsep.join(map(str, classpath)),
                    'launcher_name': 'codex', 'launcher_version': '1', 'user_properties': '{}'}
    def expand(value):
        return re.sub(r'\$\{([^}]+)\}', lambda match: placeholders.get(match[1], match[0]), value)
    def arguments(group):
        result = []
        for arg in config['arguments'][group]:
            if isinstance(arg, str):
                result.append(expand(arg))
            elif allowed(arg.get('rules')):
                values = arg['value'] if isinstance(arg['value'], list) else [arg['value']]
                result.extend(expand(value) for value in values)
        return result
    command = [str(java(config.get('javaVersion', {}).get('majorVersion', 21))), '-Xmx4G',
               '-Djava.io.tmpdir=' + str(work), '-XX:ErrorFile=' + str(work / (role + '-hs_err_pid%p.log')),
               *arguments('jvm'), config['mainClass'], *arguments('game')]
    command += ['--quickPlaySingleplayer', singleplayer] if singleplayer else ['--quickPlayMultiplayer', address]
    pid = launch(command, directory, work, role)
    manifest['processes'][role] = pid
    save(work / 'session.json', manifest)
    print(f'client_pid={pid}')

def rcon(manifest, command):
    props = properties(Path(manifest['server']) / 'server.properties')
    with socket.create_connection(('127.0.0.1', int(props['rcon.port'])), timeout=10) as sock:
        def receive(n):
            data = b''
            while len(data) < n:
                chunk = sock.recv(n - len(data))
                if not chunk:
                    raise RuntimeError('RCON closed')
                data += chunk
            return data
        def send(kind, text):
            data = struct.pack('<ii', 71, kind) + text.encode() + b'\0\0'
            sock.sendall(struct.pack('<i', len(data)) + data)
            length = struct.unpack('<i', receive(4))[0]
            return receive(length)
        auth = send(3, props['rcon.password'])
        if struct.unpack('<i', auth[:4])[0] == -1:
            raise RuntimeError('RCON authentication failed')
        response = send(2, command)[8:-2].decode(errors='replace')
        print(response)
        with (Path(manifest['_session_dir']) / 'rcon.log').open('a', encoding='utf-8') as handle:
            handle.write(time.strftime('%Y-%m-%d %H:%M:%S') + ' > ' + command + '\n' + response + '\n')

def snapshot(manifest, work, name):
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', name):
        raise ValueError('Step ID must be a safe filename')
    evidence = {}
    for side in ['client', 'server']:
        log = Path(manifest[side]) / 'logs/latest.log'
        evidence[side + '_offset'] = log.stat().st_size if log.exists() else 0
    evidence['started'] = time.time()
    evidence['status'] = 'PENDING'
    manifest['steps'][name] = evidence
    save(work / 'session.json', manifest)
    print('Baseline captured. Execute the step through the real client chat, then collect.')

def collect(manifest, work, name):
    step = manifest['steps'][name]
    dest = work / 'evidence' / name
    dest.mkdir(parents=True, exist_ok=True)
    for side in ['client', 'server']:
        text = read_tail(Path(manifest[side]) / 'logs/latest.log', step[side + '_offset'])
        (dest / (side + '.log')).write_text(text, encoding='utf-8')
    step['status'] = 'NEEDS_REVIEW'
    step['evidence'] = str(dest)
    save(work / 'session.json', manifest)
    print('Evidence collected. NEEDS_REVIEW: command acceptance alone is insufficient for PASS.')

def restore(manifest, work):
    if not local_port_free(25565):
        raise RuntimeError('Stop this session server and client before restoring jars/config')
    for pid in manifest['processes'].values():
        output = subprocess.run(['tasklist', '/FI', f'PID eq {pid}', '/FO', 'CSV', '/NH'],
                                capture_output=True, text=True).stdout
        if re.search(r'"' + str(pid) + r'"', output):
            raise RuntimeError(f'Session process {pid} is still running; close it before restore')
    for target in manifest['deployed']:
        path = Path(target)
        if path.exists() and sha(path) == manifest['sha256']:
            path.unlink()
    for backup in manifest['backups']:
        shutil.copy2(backup['backup'], backup['original'])
    for original in manifest.get('absent', []):
        path = Path(original)
        if path.exists():
            if not any(path.resolve().is_relative_to(Path(manifest[side]).resolve()) for side in ['client', 'server']):
                raise RuntimeError('Recovery path left fixed test instance')
            path.unlink()
    manifest['restored'] = time.time()
    save(work / 'session.json', manifest)
    print('Original jars and recorded configuration restored.')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['begin', 'server', 'client', 'rcon', 'step', 'collect', 'restore'])
    parser.add_argument('--client')
    parser.add_argument('--server')
    parser.add_argument('--jar')
    parser.add_argument('--session')
    parser.add_argument('--name')
    parser.add_argument('--command')
    parser.add_argument('--singleplayer')
    parser.add_argument('--username', default='CodexTester')
    parser.add_argument('--role', default='client')
    parser.add_argument('--address', default='localhost:25565')
    args = parser.parse_args()
    if args.action == 'begin':
        begin(args)
        return
    session = Path(args.session).resolve()
    assert session.is_relative_to((ROOT / 'cache/tmp/client-regression').resolve())
    manifest = json.loads(session.read_text(encoding='utf-8'))
    work = session.parent
    manifest['_session_dir'] = str(work)
    if args.action == 'server': start_server(manifest, work)
    elif args.action == 'client': start_client(manifest, work, args.singleplayer, args.username, args.role, args.address)
    elif args.action == 'rcon': rcon(manifest, args.command)
    elif args.action == 'step': snapshot(manifest, work, args.name)
    elif args.action == 'collect': collect(manifest, work, args.name)
    elif args.action == 'restore': restore(manifest, work)

if __name__ == '__main__':
    main()
