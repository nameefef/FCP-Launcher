// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
//
// Small trampoline around Fold Craft Launcher 1.3.4.4's libpojavexec.so.
// FCLBridge.dlopen falls back to linker_ns_dlopen(app_escapeNs), but that
// namespace is only created inside the exported loader_dlopen helper.
// FCLBridge.setLdLibraryPath calls android_update_LD_LIBRARY_PATH without a
// null check; on API 29+ that symbol is often hidden and the call would crash.

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "FCP"

typedef void *(*loader_dlopen_fn)(char *primary, char *secondary, int flags);
typedef void (*update_ld_path_fn)(const char *path);

static void *pojav_handle(void) {
    void *handle = dlopen("libpojavexec.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == NULL) {
        handle = dlopen("libpojavexec.so", RTLD_NOW);
    }
    return handle;
}

JNIEXPORT jboolean JNICALL
Java_fcp_launcher_launch_LinkerSupport_ensureEscapeNamespace(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    void *pojav = pojav_handle();
    if (pojav == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "libpojavexec.so is not loaded");
        return JNI_FALSE;
    }
    loader_dlopen_fn loader = (loader_dlopen_fn) dlsym(pojav, "loader_dlopen");
    if (loader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "loader_dlopen missing: %s", dlerror());
        return JNI_FALSE;
    }
    // A missing primary forces loader_dlopen to create app_escapeNs before the
    // real libjli dlopen. libdl.so is only there so the probe itself succeeds.
    char missing[] = "libfcp_missing_namespace_probe.so";
    char secondary[] = "libdl.so";
    loader(missing, secondary, RTLD_LAZY);
    return JNI_TRUE;
}

static update_ld_path_fn find_update_ld_path(void) {
    const char *names[] = {
            "android_update_LD_LIBRARY_PATH",
            "__loader_android_update_LD_LIBRARY_PATH",
    };
    void *libdl = dlopen("libdl.so", RTLD_LAZY);
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        void *symbol = NULL;
        if (libdl != NULL) {
            symbol = dlsym(libdl, names[i]);
        }
        if (symbol == NULL) {
            symbol = dlsym(RTLD_DEFAULT, names[i]);
        }
        if (symbol != NULL) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "using %s", names[i]);
            return (update_ld_path_fn) symbol;
        }
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_fcp_launcher_launch_LinkerSupport_updateLdLibraryPath(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    update_ld_path_fn update = find_update_ld_path();
    if (update == NULL) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "LD_LIBRARY_PATH linker symbol is not available");
        return JNI_FALSE;
    }
    const char *utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (utf == NULL) {
        return JNI_FALSE;
    }
    update(utf);
    (*env)->ReleaseStringUTFChars(env, path, utf);
    return JNI_TRUE;
}

struct bias_query {
    const char *needle;
    uintptr_t bias;
    int found;
};

static int find_bias(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    struct bias_query *query = data;
    if (info->dlpi_name != NULL && strstr(info->dlpi_name, query->needle) != NULL) {
        query->bias = (uintptr_t) info->dlpi_addr;
        query->found = 1;
        return 1;
    }
    return 0;
}

/* libunpack200.so keeps main in the local symbol table, so dlsym cannot see it
 * and Android 10+ refuses to exec the file. Read the ELF and call it in-process. */
static void *local_function(const char *path, const char *symbol, uintptr_t bias) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;
    ElfW(Ehdr) ehdr;
    if (read(fd, &ehdr, sizeof(ehdr)) != (ssize_t) sizeof(ehdr) ||
        memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        close(fd);
        return NULL;
    }
    size_t shbytes = (size_t) ehdr.e_shnum * sizeof(ElfW(Shdr));
    ElfW(Shdr) *shdrs = malloc(shbytes);
    void *result = NULL;
    if (shdrs == NULL ||
        lseek(fd, (off_t) ehdr.e_shoff, SEEK_SET) < 0 ||
        read(fd, shdrs, shbytes) != (ssize_t) shbytes) {
        free(shdrs);
        close(fd);
        return NULL;
    }
    for (int i = 0; i < ehdr.e_shnum; i++) {
        if (shdrs[i].sh_type != SHT_SYMTAB) continue;
        ElfW(Shdr) *strings = &shdrs[shdrs[i].sh_link];
        char *names = malloc(strings->sh_size);
        ElfW(Sym) *syms = malloc(shdrs[i].sh_size);
        if (names == NULL || syms == NULL) {
            free(names);
            free(syms);
            break;
        }
        if (lseek(fd, (off_t) strings->sh_offset, SEEK_SET) < 0 ||
            read(fd, names, strings->sh_size) != (ssize_t) strings->sh_size ||
            lseek(fd, (off_t) shdrs[i].sh_offset, SEEK_SET) < 0 ||
            read(fd, syms, shdrs[i].sh_size) != (ssize_t) shdrs[i].sh_size) {
            free(names);
            free(syms);
            break;
        }
        size_t count = shdrs[i].sh_size / sizeof(ElfW(Sym));
        for (size_t s = 0; s < count; s++) {
            if (syms[s].st_name == 0) continue;
            if ((syms[s].st_info & 0xf) != STT_FUNC) continue;
            if (strcmp(names + syms[s].st_name, symbol) == 0) {
                result = (void *) (bias + (uintptr_t) syms[s].st_value);
                break;
            }
        }
        free(names);
        free(syms);
        break;
    }
    free(shdrs);
    close(fd);
    return result;
}

typedef jint (*jli_launch_fn)(
        int argc,
        char **argv,
        int jargc,
        const char **jargv,
        int appclassc,
        const char **appclassv,
        const char *fullversion,
        const char *dotversion,
        const char *pname,
        const char *lname,
        jboolean javaargs,
        jboolean cpwildcard,
        jboolean javaw,
        jint ergo);

/* Same slate as FCL's jre_launcher.c. SIGSEGV stays SIG_IGN: ART aborts a
 * process that installs SIG_DFL for it, and the VM replaces this handler. */
static void reset_signals_for_jli(void) {
    struct sigaction clean;
    memset(&clean, 0, sizeof(clean));
    for (int sig = 1; sig < NSIG; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) continue;
        clean.sa_handler = (sig == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigaction(sig, &clean, NULL);
    }
}

JNIEXPORT jint JNICALL
Java_fcp_launcher_launch_LinkerSupport_launchJli(JNIEnv *env, jclass clazz, jstring libjliPath, jobjectArray args) {
    (void) clazz;
    if (libjliPath == NULL || args == NULL) return -1;
    const char *path = (*env)->GetStringUTFChars(env, libjliPath, NULL);
    if (path == NULL) return -2;
    jsize argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    if (argv == NULL) {
        (*env)->ReleaseStringUTFChars(env, libjliPath, path);
        return -3;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring item = (*env)->GetObjectArrayElement(env, args, i);
        const char *utf = item == NULL ? "" : (*env)->GetStringUTFChars(env, item, NULL);
        argv[i] = strdup(utf == NULL ? "" : utf);
        if (item != NULL && utf != NULL) (*env)->ReleaseStringUTFChars(env, item, utf);
        if (item != NULL) (*env)->DeleteLocalRef(env, item);
    }
    printf("FCP launchJli: reset signals\n");
    fflush(stdout);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "launchJli reset signals");
    reset_signals_for_jli();
    void *libjli = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    printf("FCP launchJli: dlopen %s -> %p\n", path, libjli);
    fflush(stdout);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "launchJli dlopen %s -> %p", path, libjli);
    jint result = -4;
    jli_launch_fn launch = NULL;
    if (libjli == NULL) {
        printf("FCP launchJli: dlopen failed: %s\n", dlerror());
        fflush(stdout);
        result = -4;
        goto done;
    }
    launch = (jli_launch_fn) dlsym(libjli, "JLI_Launch");
    if (launch == NULL) {
        printf("FCP launchJli: JLI_Launch missing: %s\n", dlerror());
        fflush(stdout);
        result = -5;
        goto done;
    }
    printf("FCP launchJli: calling JLI_Launch argc=%d\n", (int) argc);
    fflush(stdout);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "launchJli calling JLI_Launch argc=%d", (int) argc);
    result = launch(
            (int) argc,
            argv,
            0,
            NULL,
            0,
            NULL,
            "1.8.0-internal",
            "1.8",
            argv[0] == NULL ? "java" : argv[0],
            argv[0] == NULL ? "java" : argv[0],
            JNI_FALSE,
            JNI_TRUE,
            JNI_FALSE,
            0);
    printf("FCP launchJli: JLI_Launch returned %d\n", (int) result);
    fflush(stdout);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "launchJli returned %d", (int) result);
done:
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, libjliPath, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_fcp_launcher_launch_LinkerSupport_unpack200(JNIEnv *env, jclass clazz, jstring toolPath, jstring packPath, jstring jarPath) {
    (void) clazz;
    const char *tool = (*env)->GetStringUTFChars(env, toolPath, NULL);
    const char *pack = (*env)->GetStringUTFChars(env, packPath, NULL);
    const char *jar = (*env)->GetStringUTFChars(env, jarPath, NULL);
    jint result = -1;
    if (tool == NULL || pack == NULL || jar == NULL) {
        result = -2;
        goto done;
    }
    void *handle = dlopen(tool, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlopen unpack200: %s", dlerror());
        result = -3;
        goto done;
    }
    const char *base = strrchr(tool, '/');
    base = base == NULL ? tool : base + 1;
    struct bias_query query = {base, 0, 0};
    dl_iterate_phdr(find_bias, &query);
    if (!query.found) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "unpack200 load bias missing for %s", base);
        result = -4;
        goto done;
    }
    typedef int (*main_fn)(int, char **);
    main_fn entry = (main_fn) local_function(tool, "main", query.bias);
    if (entry == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "unpack200 main not found");
        result = -5;
        goto done;
    }
    char *argv[] = {(char *) tool, (char *) pack, (char *) jar, NULL};
    result = entry(3, argv);
done:
    if (jar != NULL) (*env)->ReleaseStringUTFChars(env, jarPath, jar);
    if (pack != NULL) (*env)->ReleaseStringUTFChars(env, packPath, pack);
    if (tool != NULL) (*env)->ReleaseStringUTFChars(env, toolPath, tool);
    return result;
}
