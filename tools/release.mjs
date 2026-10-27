// Build, sign and publish the app from this machine, then point latest.json at it.
//   node tools/release.mjs <version, e.g. 1.0.3> "<release notes>"
// The notes are shown in the app's update prompt. Needs gh (logged in), Java 17 and the natives in
// android/build/natives (see README). The keystore and its passwords are read from TAVERN_SIGNING_DIR
// (default ../chiyao-tavern-signing): release.keystore and password.txt ("alias: …", "store/key password: …").
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, copyFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const [version, notes] = process.argv.slice(2);
if (!/^\d+\.\d+\.\d+$/.test(version || '') || !notes) {
  console.error('usage: node tools/release.mjs <x.y.z> "<release notes>"');
  process.exit(1);
}
const tag = `v${version}`;
const win = process.platform === 'win32';
const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { cwd: root, stdio: 'inherit', ...opts });
const out = (cmd, args) => execFileSync(cmd, args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
// whether a command succeeds; some print nothing when they do
const ok = (cmd, args) => { try { out(cmd, args); return true; } catch { return false; } };

// what gets released is exactly what is on GitHub
if (out('git', ['status', '--porcelain', '--untracked-files=no'])) throw new Error('uncommitted changes; commit them first');
run('git', ['fetch', '-q', 'origin']);
const head = out('git', ['rev-parse', 'HEAD']);
if (!out('git', ['branch', '-r', '--contains', head])) throw new Error('HEAD is not pushed; push it first');
if (ok('gh', ['release', 'view', tag])) throw new Error(`release ${tag} already exists`);

const repo = out('gh', ['repo', 'view', '--json', 'nameWithOwner', '-q', '.nameWithOwner']);
const work = join(root, 'android', 'build', 'release');
mkdirSync(work, { recursive: true });
const manifestFile = join(work, 'latest.json');
if (!ok('gh', ['release', 'download', 'manifest', '-p', 'latest.json', '-D', work, '--clobber'])) writeFileSync(manifestFile, '{}\n');
const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
const versionCode = (manifest.shell?.versionCode || 0) + 1;
console.log(`${tag}, versionCode ${versionCode}`);

const signing = resolve(process.env.TAVERN_SIGNING_DIR || join(root, '..', 'chiyao-tavern-signing'));
const keystore = join(signing, 'release.keystore');
if (!existsSync(keystore)) throw new Error(`no keystore at ${keystore}`);
const field = (name) => {
  const m = new RegExp(`^${name}:\\s*(.+)$`, 'm').exec(readFileSync(join(signing, 'password.txt'), 'utf8'));
  if (!m) throw new Error(`password.txt has no "${name}:" line`);
  return m[1].trim();
};
const password = field('store/key password');

const env = {
  ...process.env,
  TAVERN_VERSION_CODE: String(versionCode),
  TAVERN_VERSION_NAME: version,
  TAVERN_MANIFEST_URL: `https://github.com/${repo}/releases/download/manifest/latest.json`,
  TAVERN_KEYSTORE: keystore,
  TAVERN_KEYSTORE_PASSWORD: password,
  TAVERN_KEY_ALIAS: field('alias'),
  TAVERN_KEY_PASSWORD: password,
};
// a .bat only runs through the shell (by full path: cmd may skip the current directory); no argument needs quoting
run(join(root, 'android', win ? 'gradlew.bat' : 'gradlew'), ['--no-daemon', 'testDebugUnitTest', 'assembleRelease'],
  { cwd: join(root, 'android'), env, shell: win });
const built = join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
// without a signing config Gradle writes app-release-unsigned.apk instead
if (!existsSync(built)) throw new Error('no signed APK was built');
const apkName = `chiyao-tavern-${tag}.apk`;
const apk = join(work, apkName);
copyFileSync(built, apk);

const notesFile = join(work, 'notes.txt');
writeFileSync(notesFile, notes + '\n');
if (!out('git', ['ls-remote', '--tags', 'origin', `refs/tags/${tag}`])) {
  if (!ok('git', ['rev-parse', '-q', '--verify', `refs/tags/${tag}`])) run('git', ['tag', '-a', tag, '-F', notesFile]);
  run('git', ['push', 'origin', tag]);
}
run('gh', ['release', 'create', tag, apk, '--title', `吃药酒馆 ${tag}`, '--notes-file', notesFile, '--verify-tag']);

const url = `https://github.com/${repo}/releases/download/${tag}/${apkName}`;
run('node', [join(root, 'tools', 'manifest.mjs'), manifestFile, 'shell', String(versionCode), version, url, apk, notesFile]);
if (!ok('gh', ['release', 'view', 'manifest'])) {
  run('gh', ['release', 'create', 'manifest', '--title', 'Update manifest', '--notes', "latest.json for the app's update check.", '--latest=false']);
}
run('gh', ['release', 'upload', 'manifest', manifestFile, '--clobber']);
console.log(`published ${tag}: ${url}`);
