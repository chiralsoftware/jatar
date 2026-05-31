package chiralsoftware.jatar;

import dev.hallock.zstd.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static chiralsoftware.jatar.UnixStat.statPath;
import static dev.hallock.zstd.ZstdCompressionParameter.COMPRESSION_LEVEL;
import static dev.hallock.zstd.ZstdCompressionParameter.NB_WORKERS;
import static java.io.File.separatorChar;
import static java.lang.System.exit;
import static java.nio.file.Files.*;

import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static java.lang.System.out;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Comparator.naturalOrder;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX;
import static org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK;

@CommandLine.Command(mixinStandardHelpOptions = true)
public class BackupCommand implements Runnable {

    static void main(String[] args) {
        final int exitCode = new CommandLine(new BackupCommand()).execute(args);
        exit(exitCode);
    }

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(
            index = "0",
            arity = "1",
            paramLabel = "OUTPUT",
            description = "Output archive file (or '-' for stdout)"
    )
    private Path output;

    @CommandLine.Parameters(
            index = "1..*",
            paramLabel = "INPUTS",
            description = "One or more files or directories to include"
    )
    private List<Path> inputs;

    @CommandLine.Option(names = {"-s", "--size"}, description = "Chunk size in GB", defaultValue = "3000")
    private long chunkSizeGb;

    @CommandLine.Option(names = {"-c", "--command"}, description = "Upload command to execute")
    private String uploadCommand;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose;

    @CommandLine.Option(names = "--threads", description = "Number of threads to use", defaultValue = "5")
    private int threadCount;

    @CommandLine.Option(names = "--zthreads", description = "Number of zstandard threads to use")
    private int zstdThreads;

    @CommandLine.Option(names = "--zlevel", description = "Compression level to use")
    private int zstdLevel;

    @CommandLine.Option(names = {"--digest"}, description = "Digest algorithm to use", defaultValue = "SHA256")
    private DigestAlgorithm digestAlgorithm;

    @CommandLine.Option(names = "--index", description = "Create an index file for the archive")
    private Path indexPath;

    @CommandLine.Option(names = "--index-command", description = "Create an index file for the archive and send to command")
    private Path indexCommand;

    enum DigestAlgorithm {
        SHA256("SHA-256"), MD5("md5"), NONE("none");
        private final String alg;
        MessageDigest messageDigest() {
            if(this == NONE) return null;
            try {
                return MessageDigest.getInstance(alg);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Unsupported digest algorithm: " + alg, e);
            }
        }
        DigestAlgorithm(String alg) { this.alg = alg;}
    }

    private void add(ExecutorService executor, ConcurrentLinkedQueue<FileRecord> queue, Path root, Path relative) {
        executor.submit(() -> {
            try {
                queue.add(FileRecord.of(root, relative, digestAlgorithm));
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void run() {
        if (output == null) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Missing required output file"
            );
        }

        if (inputs == null || inputs.isEmpty()) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "Missing required input paths"
            );
        }

        final ConcurrentLinkedQueue<FileRecord> recordQueue = new ConcurrentLinkedQueue<>();
        final ExecutorService executor = newFixedThreadPool(threadCount);

        for(Path root : inputs) {
            out.println("Back up " + root);

            try (Stream<Path> stream = walk(root)) {
                stream.filter(p -> !p.toString().contains(".ssh")).
                        filter(p -> !p.toString().contains(".cache")).
                        forEach(p -> add(executor, recordQueue, root, root.relativize(p)));
                executor.shutdown();
                executor.awaitTermination(10, MINUTES);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        final ArrayList<FileRecord> records = new ArrayList<>(recordQueue);
        records.sort(naturalOrder());
//        out.println("files: " + records.stream().map(FileRecord::toString).collect(joining("\n")));
        try {
            final OutputStream out = output.toString().equals("-") ? System.out : newOutputStream(output);

            final ZstdOutputStream zstd = new ZstdOutputStream(out, Map.of(COMPRESSION_LEVEL, zstdLevel, NB_WORKERS, zstdThreads));
            final TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd);
            tar.setLongFileMode(LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(false);

            for(FileRecord record : records) {
                if(record.isDirectory()) {
                    System.out.println("---- cool man i got this directory: " + record);
                }
                // Compute the tar entry name (relative to the input root)
                final String entryName = record.relative().toString().replace(separatorChar, '/').replaceFirst("^/", "");
                if(entryName.isEmpty()) continue;
                // check for symlinks
                if(isSymbolicLink(record.absolute())) {
                    final Path target = readSymbolicLink(record.absolute());
                    final String targetName = target.toString().replace(separatorChar, '/');
                    final TarArchiveEntry linkEntry  = new TarArchiveEntry(entryName, LF_SYMLINK);
                    linkEntry.setSize(0);
                    linkEntry.setLinkName(targetName);
                    tar.putArchiveEntry(linkEntry);
                    tar.closeArchiveEntry();
                    continue;
                }

                final TarArchiveEntry entry = new TarArchiveEntry(record.absolute());
                entry.setName(entryName);

                entry.setModTime(record.modTime());
                entry.setCreationTime(null); // otherwise we get a bunch of tar: Ignoring unknown extended header keyword 'LIBARCHIVE.creationtime'

                entry.setMode(record.mode());
                entry.setSize(record.size() );
                tar.putArchiveEntry(entry);
                if(isRegularFile(record.absolute()))  Files.copy(record.absolute(), tar);
                tar.closeArchiveEntry();
            }

            tar.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
