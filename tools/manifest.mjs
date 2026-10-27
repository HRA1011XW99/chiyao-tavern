// Update one section of latest.json (see Manifest.kt) and leave the other as it is.
//   node tools/manifest.mjs <latest.json> st <st.json> <download url>
//   node tools/manifest.mjs <latest.json> shell <versionCode> <versionName> <download url> <apk> [notes file]
// A missing latest.json starts empty. The file is rewritten in place.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const [file, section, ...args] = process.argv.slice(2);
const manifest = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');

if (section === 'st') {
  const [stJson, url] = args;
  const st = JSON.parse(readFileSync(stJson, 'utf8'));
  if (manifest.st && st.build <= manifest.st.build) throw new Error(`build ${st.build} is not newer than ${manifest.st.build}`);
  manifest.st = {
    build: st.build, version: st.version, commit: st.commit,
    url, sha256: st.sha256, size: st.size, minShell: st.minShell,
  };
} else if (section === 'shell') {
  const [versionCode, versionName, url, apk, notesFile] = args;
  manifest.shell = {
    versionCode: Number(versionCode), versionName, url, sha256: sha256(apk),
    notes: notesFile && existsSync(notesFile) ? readFileSync(notesFile, 'utf8').trim() : '',
  };
  if (!Number.isInteger(manifest.shell.versionCode)) throw new Error(`bad versionCode ${versionCode}`);
} else {
  throw new Error('section must be st or shell');
}

writeFileSync(file, JSON.stringify(manifest, null, 2) + '\n');
console.log(JSON.stringify(manifest, null, 2));
