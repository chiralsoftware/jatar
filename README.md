# jatar
Parallel tar for large backups with predictable order, an index, and independently restorable chunks

This replacement for tar is designed to be 100% compatible with Gnu Tar, while easily
performing parallel storage of independently restorable chunks compressed with 
ZStandard.

# Roadmap

Basic tar of a directory is done. Next steps:

## Independently restorable chunks

An option will be to specify a soft maximum chunk size. Only files up to this size will
be added, and then another tar file will be created. File order is predictable and each
tar chunk will be independently restorable. This means that some directory entries will need to
be duplicated in chunks, because tar cannot restore a file if the directory doesn't exist.

## Parallel upload

Chunks can be written to disk as files, or can be piped to a command. Piping to command
can occur with threads, allowing parallel uploads to S3, ssh or other options.

## Index file creation

An index JSON is created listing files, file content type, size, SHA256, and which chunk the file
is in, allowing easily restoring a specific file by only loading the specific chunk it is in.

## GraalVM native-image

This code uses FFM access to libzstd for compression. Once reachability metadata are available,
this will allow the command to be compiled to a native executable for portability and easy
execution.
