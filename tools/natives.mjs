// Copy the cross-compiled executables out of the Docker build volume into android/build/natives/,
// which the Gradle build picks up as jniLibs and assets.
//   node tools/natives.mjs            arm64 only (release)
//   node tools/natives.mjs --x86_64   also x86_64 (for the emulator)
//   node tools/natives.mjs --zip      also android/build/natives.zip, for the "natives" release CI uses
// Build them first:
//   Node: tools/node-android/build.sh <arch> <version>   git: tools/git-android/build.sh <arch>
//   toolchain: tools/llvm-android/build.sh <arch>, then tools/toolchain/build.sh <arch> <Node version>
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const REPO = resolve(import.meta.dirname, '..');
const VOL = 'chiyao-node-android';
const NODE_VERSION = readFileSync(join(REPO, 'tools/node-android/node.version'), 'utf8').trim();
const OUT = join(REPO, 'android/build/natives');
const env = { ...process.env, MSYS_NO_PATHCONV: '1' };
const archs = [['arm64', 'arm64-v8a', 'aarch64-linux-android']];
if (process.argv.includes('--x86_64')) archs.push(['x86_64', 'x86_64', 'x86_64-linux-android']);
const NDK_LIB = '/work/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib';

rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'assets'), { recursive: true });

const box = `tavern-natives-${process.pid}`;
execFileSync('docker', ['create', '--name', box, '-v', `${VOL}:/work`, 'ubuntu:24.04'], { env });
const cp = (from, to) => execFileSync('docker', ['cp', `${box}:${from}`, to], { env });
try {
  for (const [arch, abi, triple] of archs) {
    const dir = join(OUT, 'jniLibs', abi);
    mkdirSync(dir, { recursive: true });
    cp(`/work/out/node-android-${arch}/libnode.so`, join(dir, 'libnode.so'));
    // Node links the NDK's C++ runtime dynamically; the app finds it through LD_LIBRARY_PATH (Tools.kt)
    cp(`${NDK_LIB}/${triple}/libc++_shared.so`, join(dir, 'libc++_shared.so'));
    cp(`/work/out/git-android-${arch}/libgit.so`, join(dir, 'libgit.so'));
    cp(`/work/out/git-android-${arch}/libgit-remote-http.so`, join(dir, 'libgit-remote-http.so'));
    // the build toolchain for native addons (tools/llvm-android, tools/toolchain): executables and their
    // libraries next to Node, the read-only files as one zip per ABI that the app unpacks on first use
    let toolchain = 'toolchain';
    try {
      cp(`/work/out/toolchain-${arch}/toolchain.zip`, join(OUT, 'assets', `toolchain-${abi}.zip`));
      cp(`/work/out/toolchain-${arch}/jni/.`, dir);
    } catch {
      toolchain = 'NO TOOLCHAIN (native addons will not be compiled)';
    }
    console.log(`   ${abi}: node, libc++_shared, git, ${toolchain}`);
  }
  cp('/work/out/git-android-arm64/cacert.pem', join(OUT, 'assets', 'cacert.pem'));
} finally {
  execFileSync('docker', ['rm', box], { env });
}

// npm ships inside the Node source tree; the APK carries it as a zip that the app unpacks once per version.
execFileSync('docker', ['run', '--rm', '-v', `${VOL}:/work`, '-v', `${join(OUT, 'assets')}:/out`, 'ubuntu:24.04', 'bash', '-c',
  `set -e; command -v zip >/dev/null || (apt-get update -qq && apt-get install -y -qq zip >/dev/null);
   cd /work/node-${NODE_VERSION}-arm64/deps && rm -f /out/npm.zip &&
   zip -qr -X /out/npm.zip npm -x 'npm/docs/*' 'npm/man/*' 'npm/tap-snapshots/*'`], { env, stdio: 'inherit' });
const npmVersion = JSON.parse(execFileSync('docker', ['run', '--rm', '-v', `${VOL}:/work`, 'ubuntu:24.04',
  'cat', `/work/node-${NODE_VERSION}-arm64/deps/npm/package.json`], { env, encoding: 'utf8' })).version;
writeFileSync(join(OUT, 'assets', 'npm.version'), npmVersion + '\n');
console.log(`   npm ${npmVersion}`);
if (!existsSync(join(OUT, 'assets', 'npm.zip'))) throw new Error('npm.zip missing');

// --zip: also bundle android/build/natives/ as android/build/natives.zip, which CI downloads from the
// "natives" release instead of rebuilding Node and git (see README).
if (process.argv.includes('--zip')) {
  const zip = join(REPO, 'android/build/natives.zip');
  rmSync(zip, { force: true });
  execFileSync('docker', ['run', '--rm', '-v', `${OUT}:/in:ro`, '-v', `${join(REPO, 'android/build')}:/out`, 'ubuntu:24.04', 'bash', '-c',
    `set -e; command -v zip >/dev/null || (apt-get update -qq && apt-get install -y -qq zip >/dev/null);
     cd /in && zip -qr -X /out/natives.zip .`], { env, stdio: 'inherit' });
  console.log(`   ${zip}`);
}
