// Preloaded into every Node process the app starts (NODE_OPTIONS, see Tools.kt).
//
// Node built for Android has no process.getuid / getgid / getgroups (nor the effective-id variants). npm's
// isexe needs them to tell whether a file on PATH can be run, so `which` finds nothing at all and node-gyp
// reports "not found: make". The values come from os.userInfo(), which works on Android.
'use strict';
if (typeof process.getuid !== 'function') {
    try {
        const { uid, gid } = require('os').userInfo();
        process.getuid = process.geteuid = () => uid;
        process.getgid = process.getegid = () => gid;
        process.getgroups = () => [gid];
    } catch {
        // leave them missing; nothing else depends on this file
    }
}
