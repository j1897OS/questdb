package com.nfsdb.net;

import com.nfsdb.ex.NetworkError;
import com.nfsdb.misc.Files;
import com.nfsdb.misc.Net;
import com.nfsdb.misc.Os;
import com.nfsdb.misc.Unsafe;
import com.sun.xml.internal.ws.Closeable;

public final class Epoll implements Closeable {
    public static final int NUM_KEVENTS = 1024;
    public static final short SIZEOF_EVENT;
    public static final int EPOLLIN;
    public static final int EPOLLOUT;
    public static final int EPOLL_CTL_ADD;
    private static final short DATA_OFFSET;
    private static final short EVENTS_OFFSET;
    private static final int EPOLLONESHOT;
    private static final int EPOLLET;
    private final long events;
    private final long epfd;
    private boolean closed = false;
    private long _rPtr;

    public Epoll() {
        this.events = _rPtr = Unsafe.getUnsafe().allocateMemory(SIZEOF_EVENT * NUM_KEVENTS);
        this.epfd = epollCreate();
    }

    public static void main(String... args) {
        System.out.println(SIZEOF_EVENT);

        long fd;
        System.out.println(fd = Net.socketTcp(true));


        System.out.println(Net.bind(fd, 0, 9000));
        Net.listen(fd, 1024);
        Net.configureNonBlocking(fd);
        System.out.println(Net.setRcvBuf(fd, 4096));
        System.out.println(Net.setSndBuf(fd, 4096));

        long epfd = epollCreate();
        System.out.println(epfd);

        long mem = Unsafe.getUnsafe().allocateMemory(SIZEOF_EVENT);
        Unsafe.getUnsafe().putInt(mem + EVENTS_OFFSET, EPOLLIN | EPOLLET);
        Unsafe.getUnsafe().putLong(mem + DATA_OFFSET, 11111111);
        System.out.println(epollCtl(epfd, EPOLL_CTL_ADD, fd, mem));

        epollWait(epfd, mem, 1, -1);
        System.out.println(Unsafe.getUnsafe().getByte(mem + 4));
        System.out.println(Unsafe.getUnsafe().getByte(mem + 5));
        System.out.println(Unsafe.getUnsafe().getByte(mem + 6));
        System.out.println(Unsafe.getUnsafe().getByte(mem + 7));

        System.out.println(Unsafe.getUnsafe().getLong(mem + DATA_OFFSET));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        Files.close(epfd);
        Unsafe.getUnsafe().freeMemory(events);
        closed = true;
    }

    public int epollCtl(long fd, long id, int cmd, int event) {
        Unsafe.getUnsafe().putInt(events + EVENTS_OFFSET, event | EPOLLET | EPOLLONESHOT);
        Unsafe.getUnsafe().putLong(events + DATA_OFFSET, id);
        return epollCtl(epfd, cmd, fd, events);
    }

    public long getData() {
        return Unsafe.getUnsafe().getLong(_rPtr + DATA_OFFSET);
    }

    public int getEvent() {
        return Unsafe.getUnsafe().getInt(_rPtr + EVENTS_OFFSET);
    }

    public long getFd() {
        return Unsafe.getUnsafe().getInt(_rPtr + DATA_OFFSET);
    }

    public void listen(long sfd) {
        Unsafe.getUnsafe().putInt(events + EVENTS_OFFSET, EPOLLIN);
        Unsafe.getUnsafe().putLong(events + DATA_OFFSET, 0);

        if (epollCtl(epfd, EPOLL_CTL_ADD, sfd, events) < 0) {
            throw new NetworkError("Error in epoll_ctl: " + Os.errno());
        }
    }

    public int poll() {
        return epollWait(epfd, events, NUM_KEVENTS, 0);
    }

    public void setOffset(int offset) {
        this._rPtr = this.events + (long) offset;
    }

    private static native long epollCreate();

    private static native int epollCtl(long epfd, int op, long fd, long eventPtr);

    private static native int epollWait(long epfd, long eventPtr, int eventCount, int timeout);

    private static native short getDataOffset();

    private static native short getEventsOffset();

    private static native short getEventSize();

    private static native int getEPOLLIN();

    private static native int getEPOLLET();

    private static native int getEPOLLOUT();

    private static native int getEPOLLONESHOT();

    private static native int getCtlAdd();

    static {
        Os.init();
        DATA_OFFSET = getDataOffset();
        EVENTS_OFFSET = getEventsOffset();
        SIZEOF_EVENT = getEventSize();
        EPOLLIN = getEPOLLIN();
        EPOLLET = getEPOLLET();
        EPOLLOUT = getEPOLLOUT();
        EPOLLONESHOT = getEPOLLONESHOT();
        EPOLL_CTL_ADD = getCtlAdd();
    }
}
