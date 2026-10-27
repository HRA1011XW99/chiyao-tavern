// npm's script-shell on Android (npm_config_script_shell, see Tools.kt).
//
// Install scripts call commands from node_modules/.bin ("node-gyp rebuild", "prebuild-install || ..."), and
// those are files in the app's data directory, which Android does not let an app execute. So before running
// the script, every command in the node_modules/.bin directories on PATH becomes a shell function that runs
// its target through node (JS) or sh (shell scripts): reading a file is allowed, executing it is not.
//
//   tavern-sh -c "<script>" [args...]   ->   /system/bin/sh -c "<functions>\n<script>" [args...]
//   anything else                       ->   /system/bin/sh with the same arguments
//
// Programs that start such commands themselves, not through a shell (node-gyp-build spawns "node-gyp"),
// find a link with the command's name in the app's bin/ instead (Tools.kt). Called by any name other
// than its own, this program runs that command the same way: node-gyp from npm_config_node_gyp, anything
// else from the first node_modules/.bin or node-gyp-bin directory on PATH that has it.
//
// Built as libtavernsh.so so the APK installs it into nativeLibraryDir (tools/natives.mjs).
#include <dirent.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define SH "/system/bin/sh"

static char *buf;
static size_t len, cap;

static void put(const char *s) {
    size_t n = strlen(s);
    if (len + n + 1 > cap) {
        cap = (len + n + 1) * 2;
        buf = realloc(buf, cap);
        if (!buf) { perror("tavern-sh"); exit(127); }
    }
    memcpy(buf + len, s, n + 1);
    len += n;
}

// a single-quoted shell word
static void quoted(const char *s) {
    put("'");
    for (; *s; s++) {
        if (*s == '\'') put("'\\''");
        else { char c[2] = { *s, 0 }; put(c); }
    }
    put("'");
}

static int valid_name(const char *n) {
    if (!*n || *n == '.' || *n == '-') return 0;
    for (; *n; n++)
        if (!((*n >= 'a' && *n <= 'z') || (*n >= 'A' && *n <= 'Z') || (*n >= '0' && *n <= '9') ||
              *n == '_' || *n == '-' || *n == '.')) return 0;
    return 1;
}

// names already defined; the first PATH entry wins, as it would for real executables
static char **seen;
static size_t nseen;

static int defined(const char *n) {
    for (size_t i = 0; i < nseen; i++) if (!strcmp(seen[i], n)) return 1;
    seen = realloc(seen, (nseen + 1) * sizeof *seen);
    seen[nseen++] = strdup(n);
    return 0;
}

// "node" for JS files, SH for shell scripts, NULL for anything else (left to PATH)
static const char *runner(const char *path) {
    size_t n = strlen(path);
    if ((n > 3 && !strcmp(path + n - 3, ".js")) || (n > 4 && (!strcmp(path + n - 4, ".cjs") || !strcmp(path + n - 4, ".mjs"))))
        return "node";
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    char line[256] = "";
    if (!fgets(line, sizeof line, f)) line[0] = 0;
    fclose(f);
    if (strncmp(line, "#!", 2)) return NULL;
    if (strstr(line, "node")) return "node";
    if (strstr(line, "sh")) return SH;
    return NULL;
}

static void define(const char *name, const char *target) {
    const char *run = runner(target);
    if (!run || defined(name)) return;
    put(name);
    put("() { ");
    put(run);
    put(" ");
    quoted(target);
    put(" \"$@\"; }\n");
}

static void scan(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    char path[PATH_MAX], real[PATH_MAX];
    while ((e = readdir(d))) {
        if (!valid_name(e->d_name)) continue;
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        if (!realpath(path, real)) continue;
        define(e->d_name, real);
    }
    closedir(d);
}

static int script_dir(const char *dir) {
    size_t n = strlen(dir);
    return (n >= 5 && !strcmp(dir + n - 5, "/.bin")) || strstr(dir, "node-gyp-bin") != NULL;
}

// Called as <name>: run node_modules/.bin/<name> (or npm's node-gyp) through node or sh
static int shim(const char *name, int argc, char **argv) {
    char target[PATH_MAX] = "";
    const char *gyp = getenv("npm_config_node_gyp");
    if (!strcmp(name, "node-gyp") && gyp && *gyp && runner(gyp)) {
        snprintf(target, sizeof target, "%s", gyp);
    } else {
        const char *p = getenv("PATH");
        char *path = strdup(p ? p : "");
        char file[PATH_MAX];
        for (char *dir = strtok(path, ":"); dir && !*target; dir = strtok(NULL, ":")) {
            if (!script_dir(dir)) continue;
            snprintf(file, sizeof file, "%s/%s", dir, name);
            if (!realpath(file, target) || !runner(target)) target[0] = 0;
        }
    }
    if (!*target) {
        fprintf(stderr, "%s: not found\n", name);
        return 127;
    }
    const char *run = runner(target);
    char **args = calloc(argc + 2, sizeof *args);
    args[0] = (char *) run;
    args[1] = target;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    if (!strcmp(run, "node")) execvp("node", args); else execv(SH, args);
    perror(name);
    return 127;
}

int main(int argc, char **argv) {
    const char *self = strrchr(argv[0], '/');
    self = self ? self + 1 : argv[0];
    if (strcmp(self, "libtavernsh.so") && strcmp(self, "sh")) return shim(self, argc, argv);

    if (argc < 3 || strcmp(argv[1], "-c")) {
        argv[0] = "sh";
        execv(SH, argv);
        perror("tavern-sh");
        return 127;
    }
    // npm points this at node-gyp's JS entry; its node-gyp-bin wrapper would try to execute it directly
    const char *gyp = getenv("npm_config_node_gyp");
    if (gyp && *gyp && runner(gyp) && !strcmp(runner(gyp), "node")) {
        defined("node-gyp");
        put("node-gyp() { node ");
        quoted(gyp);
        put(" \"$@\"; }\n");
    }
    const char *p = getenv("PATH");
    char *path = strdup(p ? p : "");
    for (char *dir = strtok(path, ":"); dir; dir = strtok(NULL, ":")) {
        if (script_dir(dir)) scan(dir);
    }
    put(argv[2]);

    char **args = calloc(argc + 1, sizeof *args);
    args[0] = "sh";
    args[1] = "-c";
    args[2] = buf;
    for (int i = 3; i < argc; i++) args[i] = argv[i];
    execv(SH, args);
    perror("tavern-sh");
    return 127;
}
