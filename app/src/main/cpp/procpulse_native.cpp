#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <cctype>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <climits>
#include <string_view>

namespace {

int read_file_stack(const char* path, char* buf, size_t max_len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t total = 0;
    while (total < static_cast<ssize_t>(max_len - 1)) {
        ssize_t n = read(fd, buf + total, max_len - 1 - total);
        if (n <= 0) break;
        total += n;
    }
    close(fd);
    buf[total] = '\0';
    return static_cast<int>(total);
}

const char* skip_ws(const char* p) {
    while (*p == ' ' || *p == '\t') ++p;
    return p;
}

uint64_t parse_u64(const char*& p) {
    p = skip_ws(p);
    char* end = nullptr;
    unsigned long long val = std::strtoull(p, &end, 10);
    p = end ? end : p;
    return static_cast<uint64_t>(val);
}

int64_t parse_i64(const char*& p) {
    p = skip_ws(p);
    char* end = nullptr;
    long long val = std::strtoll(p, &end, 10);
    p = end ? end : p;
    return static_cast<int64_t>(val);
}

bool starts_with(const char* line, const char* prefix) {
    return std::strncmp(line, prefix, std::strlen(prefix)) == 0;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadIntFile(
    JNIEnv* env, jobject /*thiz*/, jstring path_j) {
    const char* path = env->GetStringUTFChars(path_j, nullptr);
    if (!path) return LLONG_MIN;
    char buf[128];
    int len = read_file_stack(path, buf, sizeof(buf));
    env->ReleaseStringUTFChars(path_j, path);
    if (len <= 0) return LLONG_MIN;
    char* end = nullptr;
    long long val = std::strtoll(buf, &end, 10);
    if (end == buf) return LLONG_MIN;
    return static_cast<jlong>(val);
}

JNIEXPORT jstring JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadTextFile(
    JNIEnv* env, jobject /*thiz*/, jstring path_j) {
    const char* path = env->GetStringUTFChars(path_j, nullptr);
    if (!path) return nullptr;
    char buf[512];
    int len = read_file_stack(path, buf, sizeof(buf));
    env->ReleaseStringUTFChars(path_j, path);
    if (len <= 0) return nullptr;
    while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r' || buf[len - 1] == ' ')) {
        buf[--len] = '\0';
    }
    return env->NewStringUTF(buf);
}

// Output layout:
// out[0] = coreCountRead
// out[1] = ctxt
// out[2] = intr
// For slot i = 0 (total) and 1..N (cpu0..cpuN-1), base = 3 + i * 8:
// user, nice, sys, idle, iowait, irq, softirq, steal
JNIEXPORT jint JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadProcStat(
    JNIEnv* env, jobject /*thiz*/, jlongArray out_arr) {
    char buf[16384];
    int len = read_file_stack("/proc/stat", buf, sizeof(buf));
    if (len <= 0) return -1;

    jsize arr_len = env->GetArrayLength(out_arr);
    jlong* out = env->GetLongArrayElements(out_arr, nullptr);
    if (!out) return -1;
    std::memset(out, 0, sizeof(jlong) * arr_len);

    int cores_read = 0;
    const char* p = buf;
    while (*p) {
        const char* line_end = std::strchr(p, '\n');
        size_t line_len = line_end ? static_cast<size_t>(line_end - p) : std::strlen(p);

        if (starts_with(p, "cpu")) {
            int slot = -1;
            const char* cursor = p + 3;
            if (*cursor == ' ' || *cursor == '\t') {
                slot = 0;
            } else if (std::isdigit(static_cast<unsigned char>(*cursor))) {
                char* end = nullptr;
                long idx = std::strtol(cursor, &end, 10);
                cursor = end;
                slot = static_cast<int>(idx) + 1;
                if (slot > cores_read) cores_read = slot;
            }
            int base = 3 + slot * 8;
            if (slot >= 0 && base + 7 < arr_len) {
                for (int k = 0; k < 8; ++k) {
                    out[base + k] = static_cast<jlong>(parse_u64(cursor));
                }
            }
        } else if (starts_with(p, "ctxt ") && arr_len > 1) {
            const char* cursor = p + 5;
            out[1] = static_cast<jlong>(parse_u64(cursor));
        } else if (starts_with(p, "intr ") && arr_len > 2) {
            const char* cursor = p + 5;
            out[2] = static_cast<jlong>(parse_u64(cursor));
        }

        if (!line_end) break;
        p = line_end + 1;
        (void)line_len;
    }

    if (arr_len > 0) out[0] = cores_read;
    env->ReleaseLongArrayElements(out_arr, out, 0);
    return cores_read;
}

// Output layout (all in KB):
// 0: MemTotal, 1: MemFree, 2: MemAvailable, 3: Buffers, 4: Cached,
// 5: SReclaimable, 6: SwapTotal, 7: SwapFree, 8: Active(anon), 9: Inactive(anon), 10: Slab
JNIEXPORT jboolean JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadMemInfo(
    JNIEnv* env, jobject /*thiz*/, jlongArray out_arr) {
    char buf[8192];
    int len = read_file_stack("/proc/meminfo", buf, sizeof(buf));
    if (len <= 0) return JNI_FALSE;

    jsize arr_len = env->GetArrayLength(out_arr);
    if (arr_len < 11) return JNI_FALSE;
    jlong* out = env->GetLongArrayElements(out_arr, nullptr);
    if (!out) return JNI_FALSE;
    std::memset(out, 0, sizeof(jlong) * 11);

    const char* p = buf;
    while (*p) {
        const char* line_end = std::strchr(p, '\n');
        int idx = -1;
        size_t key_len = 0;
        if (starts_with(p, "MemTotal:")) { idx = 0; key_len = 9; }
        else if (starts_with(p, "MemFree:")) { idx = 1; key_len = 8; }
        else if (starts_with(p, "MemAvailable:")) { idx = 2; key_len = 13; }
        else if (starts_with(p, "Buffers:")) { idx = 3; key_len = 8; }
        else if (starts_with(p, "Cached:")) { idx = 4; key_len = 7; }
        else if (starts_with(p, "SReclaimable:")) { idx = 5; key_len = 13; }
        else if (starts_with(p, "SwapTotal:")) { idx = 6; key_len = 10; }
        else if (starts_with(p, "SwapFree:")) { idx = 7; key_len = 9; }
        else if (starts_with(p, "Active(anon):")) { idx = 8; key_len = 13; }
        else if (starts_with(p, "Inactive(anon):")) { idx = 9; key_len = 15; }
        else if (starts_with(p, "Slab:")) { idx = 10; key_len = 5; }

        if (idx >= 0) {
            const char* cursor = p + key_len;
            out[idx] = static_cast<jlong>(parse_u64(cursor));
        }

        if (!line_end) break;
        p = line_end + 1;
    }

    env->ReleaseLongArrayElements(out_arr, out, 0);
    return JNI_TRUE;
}

// out[0] = some avg10, out[1] = full avg10
JNIEXPORT jboolean JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadPsiMemory(
    JNIEnv* env, jobject /*thiz*/, jdoubleArray out_arr) {
    char buf[512];
    int len = read_file_stack("/proc/pressure/memory", buf, sizeof(buf));
    if (len <= 0) return JNI_FALSE;

    jsize arr_len = env->GetArrayLength(out_arr);
    if (arr_len < 2) return JNI_FALSE;
    jdouble* out = env->GetDoubleArrayElements(out_arr, nullptr);
    if (!out) return JNI_FALSE;
    out[0] = 0.0;
    out[1] = 0.0;

    const char* some = std::strstr(buf, "some avg10=");
    if (some) out[0] = std::strtod(some + 11, nullptr);
    const char* full = std::strstr(buf, "full avg10=");
    if (full) out[1] = std::strtod(full + 11, nullptr);

    env->ReleaseDoubleArrayElements(out_arr, out, 0);
    return JNI_TRUE;
}

// out[0]=orig_data_size, out[1]=compr_data_size, out[2]=mem_used_total (bytes)
JNIEXPORT jboolean JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadZramMmStat(
    JNIEnv* env, jobject /*thiz*/, jlongArray out_arr) {
    char buf[256];
    int len = read_file_stack("/sys/block/zram0/mm_stat", buf, sizeof(buf));
    if (len <= 0) return JNI_FALSE;

    jsize arr_len = env->GetArrayLength(out_arr);
    if (arr_len < 3) return JNI_FALSE;
    jlong* out = env->GetLongArrayElements(out_arr, nullptr);
    if (!out) return JNI_FALSE;

    const char* cursor = buf;
    out[0] = static_cast<jlong>(parse_u64(cursor));
    out[1] = static_cast<jlong>(parse_u64(cursor));
    out[2] = static_cast<jlong>(parse_u64(cursor));

    env->ReleaseLongArrayElements(out_arr, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeListPids(
    JNIEnv* env, jobject /*thiz*/, jintArray out_pids) {
    DIR* dir = opendir("/proc");
    if (!dir) return 0;

    jsize max_pids = env->GetArrayLength(out_pids);
    jint* out = env->GetIntArrayElements(out_pids, nullptr);
    if (!out) {
        closedir(dir);
        return 0;
    }

    int count = 0;
    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr && count < max_pids) {
        const char* name = entry->d_name;
        if (!std::isdigit(static_cast<unsigned char>(name[0]))) continue;
        char* end = nullptr;
        long pid = std::strtol(name, &end, 10);
        if (end && *end == '\0' && pid > 0) {
            out[count++] = static_cast<jint>(pid);
        }
    }

    closedir(dir);
    env->ReleaseIntArrayElements(out_pids, out, 0);
    return count;
}

// out[0]=ppid, out[1]=utime, out[2]=stime, out[3]=nice, out[4]=num_threads,
// out[5]=starttime, out[6]=rss_kb, out[7]=oom_score_adj, out[8]=fd_count,
// out[9]=pss_kb, out[10]=uss_kb, out[11]=swap_pss_kb
JNIEXPORT jboolean JNICALL
Java_com_example_procpulse_core_nativebridge_NativeProcReader_nativeReadProcessDetail(
    JNIEnv* env, jobject /*thiz*/, jint pid, jlongArray out_arr) {
    jsize arr_len = env->GetArrayLength(out_arr);
    if (arr_len < 12) return JNI_FALSE;

    char path[96];
    char buf[4096];

    std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    int len = read_file_stack(path, buf, sizeof(buf));
    if (len <= 0) return JNI_FALSE;

    jlong* out = env->GetLongArrayElements(out_arr, nullptr);
    if (!out) return JNI_FALSE;
    std::memset(out, 0, sizeof(jlong) * 12);
    out[7] = LLONG_MIN;

    const char* rparen = std::strrchr(buf, ')');
    if (rparen && rparen[1] == ' ') {
        const char* p = rparen + 2;
        // field 3: state (char), skip it
        p = skip_ws(p);
        if (*p) ++p;
        int64_t ppid = parse_i64(p);
        out[0] = ppid;
        // Skip fields 5..13 (9 ints)
        for (int i = 5; i <= 13; ++i) parse_i64(p);
        uint64_t utime = parse_u64(p);  // 14
        uint64_t stime = parse_u64(p);  // 15
        parse_i64(p); // cutime 16
        parse_i64(p); // cstime 17
        parse_i64(p); // priority 18
        int64_t nice_val = parse_i64(p); // 19
        int64_t threads = parse_i64(p); // 20
        parse_i64(p); // itrealvalue 21
        uint64_t starttime = parse_u64(p); // 22
        out[1] = static_cast<jlong>(utime);
        out[2] = static_cast<jlong>(stime);
        out[3] = static_cast<jlong>(nice_val);
        out[4] = static_cast<jlong>(threads);
        out[5] = static_cast<jlong>(starttime);
    }

    std::snprintf(path, sizeof(path), "/proc/%d/statm", pid);
    if (read_file_stack(path, buf, sizeof(buf)) > 0) {
        const char* p = buf;
        parse_u64(p); // size
        uint64_t resident_pages = parse_u64(p);
        long page_kb = sysconf(_SC_PAGESIZE) / 1024;
        if (page_kb <= 0) page_kb = 4;
        out[6] = static_cast<jlong>(resident_pages * static_cast<uint64_t>(page_kb));
    }

    std::snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
    if (read_file_stack(path, buf, sizeof(buf)) > 0) {
        const char* p = buf;
        out[7] = parse_i64(p);
    }

    std::snprintf(path, sizeof(path), "/proc/%d/fd", pid);
    DIR* fd_dir = opendir(path);
    if (fd_dir) {
        int fd_count = 0;
        struct dirent* e = nullptr;
        while ((e = readdir(fd_dir)) != nullptr) {
            if (std::isdigit(static_cast<unsigned char>(e->d_name[0]))) {
                ++fd_count;
            }
        }
        closedir(fd_dir);
        out[8] = fd_count;
    }

    std::snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", pid);
    if (read_file_stack(path, buf, sizeof(buf)) > 0) {
        uint64_t pss = 0, pclean = 0, pdirty = 0, swappss = 0;
        const char* p = buf;
        while (*p) {
            const char* line_end = std::strchr(p, '\n');
            if (starts_with(p, "Pss:")) {
                const char* c = p + 4;
                pss = parse_u64(c);
            } else if (starts_with(p, "Private_Clean:")) {
                const char* c = p + 14;
                pclean = parse_u64(c);
            } else if (starts_with(p, "Private_Dirty:")) {
                const char* c = p + 14;
                pdirty = parse_u64(c);
            } else if (starts_with(p, "SwapPss:")) {
                const char* c = p + 8;
                swappss = parse_u64(c);
            }
            if (!line_end) break;
            p = line_end + 1;
        }
        out[9] = static_cast<jlong>(pss);
        out[10] = static_cast<jlong>(pclean + pdirty);
        out[11] = static_cast<jlong>(swappss);
    }

    env->ReleaseLongArrayElements(out_arr, out, 0);
    return JNI_TRUE;
}

} // extern "C"
