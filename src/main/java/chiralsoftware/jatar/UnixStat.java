package chiralsoftware.jatar;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.Arena.ofConfined;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class UnixStat {

    static final GroupLayout STAT_LAYOUT = structLayout(
            JAVA_LONG.withName("st_dev"),
            JAVA_LONG.withName("st_ino"),
            JAVA_LONG.withName("st_nlink"),
            JAVA_INT.withName("st_mode"),
            JAVA_INT.withName("st_uid"),
            JAVA_INT.withName("st_gid"),
            JAVA_INT.withName("__pad0"),
            JAVA_LONG.withName("st_rdev"),
            JAVA_LONG.withName("st_size"),
            JAVA_LONG.withName("st_blksize"),
            JAVA_LONG.withName("st_blocks"),
            // timespec structs:
            structLayout(
                    JAVA_LONG.withName("tv_sec"),
                    JAVA_LONG.withName("tv_nsec")
            ).withName("st_atim"),
            structLayout(
                    JAVA_LONG.withName("tv_sec"),
                    JAVA_LONG.withName("tv_nsec")
            ).withName("st_mtim"),
            structLayout(
                    JAVA_LONG.withName("tv_sec"),
                    JAVA_LONG.withName("tv_nsec")
            ).withName("st_ctim"),
            sequenceLayout(3, JAVA_LONG).withName("__unused")
    );

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = Linker.nativeLinker().defaultLookup();

    private static final MethodHandle STAT = LINKER.downcallHandle(
            LIBC.find("stat").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, // return int
                    ValueLayout.ADDRESS,                    // const char* path
                    ValueLayout.ADDRESS                     // struct stat*
            )
    );

    record StatInfo(int uid, int gid) {}

    static StatInfo statPath(Path p) throws Throwable {
        try (Arena arena = ofConfined()) {
            final MemorySegment path = arena.allocateFrom(p.toString());
            final MemorySegment buf  = arena.allocate(STAT_LAYOUT);

            int res = (int) STAT.invoke(path, buf);
            if (res != 0) {
                throw new IOException("stat failed for " + p);
            }

            int uid = buf.get(JAVA_INT, STAT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("st_uid")));
            int gid = buf.get(JAVA_INT, STAT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("st_gid")));

            return new StatInfo(uid, gid);
        }
    }

}
