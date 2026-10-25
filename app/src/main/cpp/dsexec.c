// Play sürümünde bileşen programları için LD_PRELOAD kitaplığı (PLAN Faz 19, 19.4).
//
// Play sürümünde bileşenin dosyaları uygulamanın yerel kitaplık klasöründe düz bir listede durur
// (`<kitaplık klasörü>/libpostgres_7.so`); paket klasöründe (`<paket>/bin/postgres`) yalnızca onlara giden sembolik
// bağlantılar vardır (bkz. ComponentManager). Kendi yerini realpath() ile bulup komşu dosyalarını göreli arayan
// programlar (initdb `postgres`'i, postgres `share/`'ı) bu yüzden kitaplık klasörüne bakar ve bulamaz. Bu kitaplık
// realpath()'i sarar: sonuç kitaplık klasörüne (DS_LIBDIR) düşüyorsa ve verilen yolun son parçası bir bağlantıysa,
// bağlantının kendi (paket klasöründeki) yolu döner; ara klasörler yine çözülür. Diğer her durumda gerçek realpath.
//
// Aynı nedenle readlink("/proc/self/exe") (Java başlatıcısı JDK klasörünü buradan bulur) exec'e verilen yolu
// (AT_EXECFN) döndürür, dladdr() de kitaplık klasöründeki dosyanın paketteki yolunu (HotSpot java.home'u kendi
// kitaplığının yerinden çıkarır); eşleme modülün `lib<id>_files.so` dizininden, paket klasörü DS_PKG'den.
//
// Ayrıca open("/dev/ashmem"): Android 10+'ı hedefleyen uygulamalar bu aygıtı açamaz; Termux'un libandroid-shmem'i
// (Postgres'in paylaşılan belleği) açamayınca döngüye girer. Uygulamaların açabildiği `/dev/ashmem<boot_id>`'ye
// (Android'in kendi kitaplıklarının kullandığı ad) yönlendirilir.
#undef _FORTIFY_SOURCE  // open()'ı tanımlayabilmek için (bionic'in güvenlikli satır içi open'ı devre dışı)
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <unistd.h>

static char *(*real_realpath)(const char *, char *);

char *realpath(const char *path, char *resolved) {
    if (!real_realpath) real_realpath = (char *(*)(const char *, char *)) dlsym(RTLD_NEXT, "realpath");
    char *out = real_realpath(path, resolved);
    const char *libdir = getenv("DS_LIBDIR");
    struct stat st;
    if (!out || !libdir || !*libdir || strncmp(out, libdir, strlen(libdir)) != 0) return out;
    if (lstat(path, &st) != 0 || !S_ISLNK(st.st_mode)) return out;

    char dir[PATH_MAX], parent[PATH_MAX], logical[PATH_MAX];
    const char *base = strrchr(path, '/');
    if (base) {
        size_t n = (size_t) (base - path);
        if (n >= sizeof dir) return out;
        memcpy(dir, path, n);
        dir[n] = '\0';
        if (n == 0) strcpy(dir, "/");
        base++;
    } else {
        strcpy(dir, ".");
        base = path;
    }
    if (!real_realpath(dir, parent)) return out;
    if (snprintf(logical, sizeof logical, "%s/%s", strcmp(parent, "/") == 0 ? "" : parent, base) >= (int) sizeof logical) return out;
    if (resolved) {
        strcpy(resolved, logical);  // resolved en az PATH_MAX (realpath sözleşmesi)
        return resolved;
    }
    free(out);
    return strdup(logical);
}

static int (*real_open)(const char *, int, ...);

int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    if (!real_open) real_open = (int (*)(const char *, int, ...)) dlsym(RTLD_NEXT, "open");
    if (path && strcmp(path, "/dev/ashmem") == 0) {
        char dev[64] = "/dev/ashmem";
        int id = real_open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC);
        if (id >= 0) {
            size_t len = strlen(dev);
            ssize_t n = read(id, dev + len, sizeof dev - len - 1);
            close(id);
            if (n > 0) {
                dev[len + (size_t) n] = '\0';
                dev[strcspn(dev, "\n")] = '\0';
                int fd = real_open(dev, flags, mode);
                if (fd >= 0) return fd;
            }
        }
    }
    return real_open(path, flags, mode);
}

static ssize_t (*real_readlink)(const char *, char *, size_t);

static int in_libdir(const char *path) {
    const char *libdir = getenv("DS_LIBDIR");
    return libdir && *libdir && path && strncmp(path, libdir, strlen(libdir)) == 0;
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    if (!real_readlink) real_readlink = (ssize_t (*)(const char *, char *, size_t)) dlsym(RTLD_NEXT, "readlink");
    ssize_t n = real_readlink(path, buf, size);
    if (n <= 0 || (size_t) n >= size || strcmp(path, "/proc/self/exe") != 0) return n;
    char target[PATH_MAX];
    memcpy(target, buf, (size_t) n);
    target[n] = '\0';
    const char *execfn = (const char *) getauxval(AT_EXECFN);
    struct stat st;
    if (!in_libdir(target) || !execfn || execfn[0] != '/' || lstat(execfn, &st) != 0 || !S_ISLNK(st.st_mode)) return n;
    size_t len = strlen(execfn);
    if (len > size) return n;
    memcpy(buf, execfn, len);  // readlink sonu NUL ile bitirmez
    return (ssize_t) len;
}

// lib<id>_<n>.so -> paketteki yol (DS_PKG/<yol>); bulunamazsa NULL. Sonuçlar süreç boyunca saklanır (az sayıda).
static pthread_mutex_t map_lock = PTHREAD_MUTEX_INITIALIZER;
static struct { char *lib, *path; } mapped[64];
static int mapped_count;

static const char *logical_path(const char *libpath) {
    const char *pkg = getenv("DS_PKG"), *libdir = getenv("DS_LIBDIR");
    const char *name = strrchr(libpath, '/');
    if (!pkg || !libdir || !name) return NULL;
    name++;
    const char *under = strrchr(name, '_');
    if (strncmp(name, "lib", 3) != 0 || !under || under <= name + 3) return NULL;
    const char *found = NULL;
    pthread_mutex_lock(&map_lock);
    for (int i = 0; i < mapped_count && !found; i++)
        if (strcmp(mapped[i].lib, libpath) == 0) found = mapped[i].path;
    if (!found && mapped_count < (int) (sizeof mapped / sizeof mapped[0])) {
        char index[PATH_MAX], needle[NAME_MAX + 8];
        snprintf(index, sizeof index, "%s/%.*s_files.so", libdir, (int) (under - name), name);
        snprintf(needle, sizeof needle, "\":\"%s\"", name);
        int fd = open(index, O_RDONLY | O_CLOEXEC);
        struct stat st;
        char *json = NULL;
        if (fd >= 0 && fstat(fd, &st) == 0 && (json = malloc((size_t) st.st_size + 1)) != NULL) {
            ssize_t got = read(fd, json, (size_t) st.st_size);
            json[got > 0 ? got : 0] = '\0';
            char *hit = strstr(json, needle);
            if (hit) {
                char *start = hit;
                while (start > json && start[-1] != '"') start--;
                char path[PATH_MAX];
                if (snprintf(path, sizeof path, "%s/%.*s", pkg, (int) (hit - start), start) < (int) sizeof path) {
                    mapped[mapped_count].lib = strdup(libpath);
                    mapped[mapped_count].path = strdup(path);
                    found = mapped[mapped_count++].path;
                }
            }
        }
        free(json);
        if (fd >= 0) close(fd);
    }
    pthread_mutex_unlock(&map_lock);
    return found;
}

static int (*real_dladdr)(const void *, Dl_info *);

int dladdr(const void *addr, Dl_info *info) {
    if (!real_dladdr) real_dladdr = (int (*)(const void *, Dl_info *)) dlsym(RTLD_NEXT, "dladdr");
    int ok = real_dladdr(addr, info);
    if (ok && in_libdir(info->dli_fname)) {
        const char *path = logical_path(info->dli_fname);
        if (path) info->dli_fname = path;
    }
    return ok;
}
