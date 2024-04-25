package io.questdb;

import io.questdb.cairo.CairoException;
import io.questdb.network.NetworkError;
import io.questdb.std.Files;
import io.questdb.std.MemoryTag;
import io.questdb.std.Unsafe;
import io.questdb.std.str.Path;

public class KqueueFileWatcher implements FileWatcher {
    private final int bufferSize;
    private final long event;
    private final long eventList;
    private final int fd;
    private final int kq;
    private boolean closed;

    public KqueueFileWatcher(CharSequence filePath) {
        try (Path p = new Path()) {
            p.of(filePath).$();
            this.fd = Files.openRO(p);
            if (this.fd < 0) {
                throw CairoException.critical(this.fd).put("could not open file [path=").put(filePath).put(']');
            }
        }

        this.event = KqueueAccessor.evSet(
                this.fd,
                KqueueAccessor.EVFILT_VNODE,
                KqueueAccessor.EV_ADD | KqueueAccessor.EV_CLEAR,
                KqueueAccessor.NOTE_WRITE,
                0
        );

        kq = KqueueAccessor.kqueue();
        if (kq < 0) {
            throw NetworkError.instance(kq, "could not create kqueue");
        }
        Files.bumpFileCount(this.kq);

        this.bufferSize = KqueueAccessor.SIZEOF_KEVENT;
        this.eventList = Unsafe.calloc(bufferSize, MemoryTag.NATIVE_IO_DISPATCHER_RSS);

        // Register event with queue
        int res = KqueueAccessor.keventRegister(
                kq,
                event,
                1
        );
        if (res < 0) {
            throw NetworkError.instance(kq, "could not create new event");
        }

    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Files.close(kq);
            Files.close(fd);
            Unsafe.free(this.eventList, bufferSize, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
            Unsafe.free(this.event, KqueueAccessor.SIZEOF_KEVENT, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
        }
    }

    @Override
    public void waitForChange(FileWatcherCallback callback) throws FileWatcherException {
        // Blocks until there is a change in the watched dir
        int res = KqueueAccessor.keventGetBlocking(
                kq,
                eventList,
                1
        );
        if (closed) {
            return;
        }
        if (res < 0) {
            throw new FileWatcherException("kevent", res);
        }
        callback.onFileChanged();

    }
}
