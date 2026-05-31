package chiralsoftware.jatar;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static chiralsoftware.jatar.UnixStat.statPath;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.readAttributes;
import static java.nio.file.attribute.PosixFilePermission.*;

record FileRecord(Path absolute, Path relative, long modTime, long size, String digest, int mode, int uid, int guid, String owner, String group)
        implements Comparable<FileRecord> {

    @Override
    public int compareTo(FileRecord other) {
        // 1. Compare depth (number of path elements)
        final int depth1 = relative.getNameCount();
        final int depth2 = other.relative.getNameCount();
        final int cmp = Integer.compare(depth1, depth2);
        if (cmp != 0) return cmp;

        // 2. Directories come before files
        final boolean thisIsDir = isDirectory();
        final boolean otherIsDir = other.isDirectory();
        if (thisIsDir != otherIsDir) return thisIsDir ? -1 : 1;

        return relative.toString().compareTo(other.relative.toString());
    }

    @Override
    public String toString() {
        return "FileRecord{" +
                "relative=" + relative +
                ", size=" + size +
                ", digest='" + digest + '\'' +
                '}';
    }

    public boolean isDirectory() {
        return Files.isDirectory(absolute);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final FileRecord that = (FileRecord) o;
        return size == that.size && relative.equals(that.relative) && digest.equals(that.digest);
    }

    @Override
    public int hashCode() {
        return relative.hashCode();
    }

    static FileRecord of(Path root, Path relative, BackupCommand.DigestAlgorithm algorithm) throws Throwable {
        final Path absolute = root.resolve(relative);

        final int mode = toMode(Files.getPosixFilePermissions(absolute));
        final UnixStat.StatInfo stat = statPath(absolute);
        final BasicFileAttributes attrs = readAttributes(absolute, BasicFileAttributes.class);
        final long size = attrs.isDirectory() ? 0 : Files.size(absolute);

        final PosixFileAttributes posixAttributes = Files.readAttributes(absolute, PosixFileAttributes.class);

        final String digest;
        if(algorithm == BackupCommand.DigestAlgorithm.NONE || !Files.isRegularFile(absolute)) {
            digest = null;
        } else {
            final MessageDigest md = algorithm.messageDigest();
            final byte[] buffer = new byte[8192];
            int read;
            // get file stat first
            final InputStream in = newInputStream(absolute);
            while ((read = in.read(buffer)) != -1) md.update(buffer, 0, read);
            in.close();
            digest = HexFormat.of().formatHex(md.digest());
        }

        return new FileRecord(absolute, relative, attrs.lastModifiedTime().toMillis(),
                size, digest, mode, stat.uid(), stat.gid(), posixAttributes.owner().getName(),
                posixAttributes.group().getName());
    }

    private static int toMode(Set<PosixFilePermission> perms) {
        int mode = 0;
        if (perms.contains(OWNER_READ))    mode |= 0400;
        if (perms.contains(OWNER_WRITE))   mode |= 0200;
        if (perms.contains(OWNER_EXECUTE)) mode |= 0100;
        if (perms.contains(GROUP_READ))    mode |= 0040;
        if (perms.contains(GROUP_WRITE))   mode |= 0020;
        if (perms.contains(GROUP_EXECUTE)) mode |= 0010;
        if (perms.contains(OTHERS_READ))   mode |= 0004;
        if (perms.contains(OTHERS_WRITE))  mode |= 0002;
        if (perms.contains(OTHERS_EXECUTE))mode |= 0001;
        return mode;
    }

}
