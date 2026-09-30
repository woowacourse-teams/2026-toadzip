package com.toadzip.backend.announcement.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Owns the temporary bytes and the request slot until delivery finishes. */
public final class TemporaryAttachment implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final Runnable onClose;
    private long size;
    private boolean closed;

    public TemporaryAttachment(Runnable onClose) throws IOException {
        this.path = Files.createTempFile("toadzip-attachment-", ".tmp");
        this.onClose = onClose;
        try {
            this.channel = FileChannel.open(path, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
    }

    public synchronized void append(ByteBuffer buffer) throws IOException {
        if (closed) {
            throw new IOException("Attachment already closed");
        }
        while (buffer.hasRemaining()) {
            size += channel.write(buffer);
        }
    }

    public synchronized long size() {
        return size;
    }

    public byte[] prefix() throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(1024);
        }
    }

    public void transferTo(OutputStream output) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(output);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            try {
                channel.close();
            } finally {
                Files.deleteIfExists(path);
            }
        } finally {
            onClose.run();
        }
    }
}
