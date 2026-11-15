"""Starts an installed Forge client without the official launcher.

Used for the acceptance check of the packaged JAR: the launcher folder is the one filled by
`java -jar forge-1.20.1-47.4.28-installer.jar --installClient <folder>`, the game folder holds `mods/` and `options.txt`.
Missing vanilla libraries are downloaded from the URLs in Mojang's version file and checked against its SHA-1 sums.

    python scripts/packaged_client.py --java <java> --launcher <folder> --game <folder> --assets <assets root>
                                      [--jvm=-Dname=value ...] [--stop-after SECONDS]
"""
import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request

MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
MINECRAFT = '1.20.1'
FORGE = '1.20.1-forge-47.4.28'
SYSTEM = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'osx'}[platform.system()]


def sha1(path):
    with open(path, 'rb') as source:
        return hashlib.sha1(source.read()).hexdigest()


def fetch(url, path, expected):
    if os.path.exists(path) and sha1(path) == expected:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as response, open(path, 'wb') as target:
        target.write(response.read())
    if sha1(path) != expected:
        sys.exit('Checksum mismatch for ' + url)


def allowed(entry):
    """Applies the launcher rules for this system. Optional launcher features such as demo mode stay off."""
    rules = entry.get('rules')
    if not rules:
        return True
    verdict = False
    for rule in rules:
        system = rule.get('os', {})
        if 'features' in rule or 'arch' in system or system.get('name', SYSTEM) != SYSTEM:
            continue
        verdict = rule['action'] == 'allow'
    return verdict


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--java', required=True)
    parser.add_argument('--launcher', required=True)
    parser.add_argument('--game', required=True)
    parser.add_argument('--assets', required=True)
    parser.add_argument('--jvm', action='append', default=[])
    parser.add_argument('--stop-after', type=int, help='stop a client that is still running after this many seconds')
    options = parser.parse_args()

    def folder(path):
        return os.path.abspath(path).replace('\\', '/')

    launcher, game, assets = folder(options.launcher), folder(options.game), folder(options.assets)
    libraries = launcher + '/libraries'
    with open('%s/versions/%s/%s.json' % (launcher, FORGE, FORGE), encoding='utf-8') as source:
        forge = json.load(source)
    vanilla_file = '%s/versions/%s/%s.json' % (launcher, MINECRAFT, MINECRAFT)
    if not os.path.exists(vanilla_file):
        with urllib.request.urlopen(MANIFEST, timeout=120) as response:
            listed = next(version for version in json.load(response)['versions'] if version['id'] == MINECRAFT)
        fetch(listed['url'], vanilla_file, listed['sha1'])
    with open(vanilla_file, encoding='utf-8') as source:
        vanilla = json.load(source)

    classpath, seen = [], set()
    for library in forge['libraries'] + vanilla['libraries']:
        parts = library['name'].split(':')
        key = ':'.join(parts[:2] + parts[3:])
        if key in seen or not allowed(library):
            continue
        seen.add(key)
        artifact = library['downloads']['artifact']
        path = libraries + '/' + artifact['path']
        if artifact.get('url'):
            fetch(artifact['url'], path, artifact['sha1'])
        elif not os.path.exists(path):
            sys.exit('The Forge installer did not create ' + path)
        classpath.append(path)

    values = {
        'natives_directory': game + '/natives', 'launcher_name': 'buildersmod-acceptance', 'launcher_version': '1',
        'classpath': os.pathsep.join(classpath), 'library_directory': libraries, 'classpath_separator': os.pathsep,
        'version_name': FORGE, 'auth_player_name': 'Dev', 'game_directory': game, 'assets_root': assets,
        'assets_index_name': vanilla['assetIndex']['id'], 'auth_uuid': '00000000-0000-0000-0000-000000000000',
        'auth_access_token': '0', 'clientid': '', 'auth_xuid': '', 'user_type': 'legacy', 'version_type': 'release',
    }

    def expand(arguments):
        flat = []
        for argument in arguments:
            if isinstance(argument, str):
                flat.append(argument)
            elif allowed(argument):
                flat.extend([argument['value']] if isinstance(argument['value'], str) else argument['value'])
        for name, value in values.items():
            flat = [argument.replace('${%s}' % name, value) for argument in flat]
        return flat

    os.makedirs(game + '/natives', exist_ok=True)
    command = [options.java, '-Xmx3G'] + options.jvm
    command += expand(vanilla['arguments']['jvm']) + expand(forge['arguments']['jvm']) + [forge['mainClass']]
    command += expand(vanilla['arguments']['game']) + expand(forge['arguments']['game']) + ['--width', '1280', '--height', '720']
    with open(game + '/packaged-client.log', 'w', encoding='utf-8', errors='replace') as log:
        client = subprocess.Popen(command, cwd=game, stdout=log, stderr=subprocess.STDOUT)
        if options.stop_after is None:
            return client.wait()
        deadline = time.time() + options.stop_after
        while time.time() < deadline and client.poll() is None:
            time.sleep(1)
        if client.poll() is not None:
            return client.returncode or 1
        client.terminate()
        client.wait()
        return 0


if __name__ == '__main__':
    sys.exit(main())
