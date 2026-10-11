package com.google.appinventor.components.runtime.util;
import com.google.appinventor.components.runtime.Form;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class MediaUtil {
  public static final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
  public static final List<String> paths = Collections.synchronizedList(new ArrayList<String>());
  public static volatile byte[] data = new byte[128];
  public static volatile boolean openFails, readFails, blockRead;
  public static volatile CountDownLatch readEntered = new CountDownLatch(1), readRelease = new CountDownLatch(1);
  public static InputStream openMedia(Form form, String path) throws IOException {
    paths.add(path);
    if (openFails) throw new IOException("Image is inaccessible");
    opened.incrementAndGet();
    final byte[] source = data;
    return new InputStream() {
      private int offset;
      private volatile boolean disposed;
      public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
      public int read(byte[] target, int start, int length) throws IOException {
        if (blockRead) {
          readEntered.countDown();
          try { if (!readRelease.await(3, TimeUnit.SECONDS)) throw new IOException("Test image read gate timed out"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        if (disposed) throw new IOException("Image stream closed");
        if (readFails) throw new IOException("Image read failed");
        if (offset == source.length) return -1;
        int count = Math.min(length, source.length - offset); System.arraycopy(source, offset, target, start, count); offset += count; return count;
      }
      public synchronized void close() {
        if (!disposed) { disposed = true; closed.incrementAndGet(); readRelease.countDown(); }
      }
    };
  }
  public static void reset() {
    if (opened.get() != closed.get()) throw new IllegalStateException("Previous test leaked an image input stream");
    opened.set(0); closed.set(0); paths.clear(); data = new byte[128];
    openFails = readFails = blockRead = false;
    readEntered = new CountDownLatch(1); readRelease = new CountDownLatch(1);
  }
}
