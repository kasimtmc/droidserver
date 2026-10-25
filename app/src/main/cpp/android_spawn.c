// Termux'un libandroid-spawn'ının yerine (Play sürümü, OpenJDK bileşeni): Termux derlemesi 4 KB hizalı, Play ise 64 bit
// kitaplıkların 16 KB hizalı olmasını istiyor. OpenJDK bu kitaplıktan yalnızca posix_spawn'ı kullanıyor (libjava,
// libjvm). Android 9+'da sistemin kendi posix_spawn'ına iletilir; Android 8'de JDK'nın tek kullanımı olan
// "dosya işlemi ve özellik yok" hali vfork + execve ile yapılır, diğerleri ENOSYS.
#include <dlfcn.h>
#include <errno.h>
#include <sys/types.h>
#include <unistd.h>

typedef int (*spawn_fn)(pid_t *, const char *, const void *, const void *, char *const[], char *const[]);

__attribute__((visibility("default")))
int posix_spawn(pid_t *pid, const char *path, const void *file_actions, const void *attrp, char *const argv[], char *const envp[]) {
    static spawn_fn libc_spawn;
    static int looked;
    if (!looked) {
        void *libc = dlopen("libc.so", RTLD_NOW | RTLD_NOLOAD);
        if (libc) libc_spawn = (spawn_fn) dlsym(libc, "posix_spawn");
        looked = 1;
    }
    if (libc_spawn) return libc_spawn(pid, path, file_actions, attrp, argv, envp);
    if (file_actions || attrp) return ENOSYS;
    pid_t child = vfork();
    if (child < 0) return errno;
    if (child == 0) {
        execve(path, argv, envp);
        _exit(127);
    }
    if (pid) *pid = child;
    return 0;
}
