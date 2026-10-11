package android.media;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.*;
public class AudioTrack {
  public static final int MODE_STREAM = 1, STATE_INITIALIZED = 1, PLAYSTATE_STOPPED = 1, PLAYSTATE_PLAYING = 3;
  public static final int WRITE_NON_BLOCKING = 1;
  public static final List<String> nativeCalls = Collections.synchronizedList(new ArrayList<String>());
  public static final AtomicInteger created = new AtomicInteger(), released = new AtomicInteger();
  public static final AtomicInteger totalWrittenFrames = new AtomicInteger();
  public static volatile boolean blockWrites, autoAdvanceHead = true;
  public static volatile int writeFailure, maxWriteBytes = Integer.MAX_VALUE, requestedBufferBytes, framesAtFirstPlay;
  public static volatile CountDownLatch writeEntered = new CountDownLatch(1), pauseEntered, pauseRelease;
  private static final List<AudioTrack> tracks = Collections.synchronizedList(new ArrayList<AudioTrack>());
  private final CountDownLatch unblockWrite = new CountDownLatch(1);
  private volatile boolean disposed, playing;
  private int played;
  private static void nativeCall(String method) { nativeCalls.add(method + "@" + Thread.currentThread().getName()); }
  public AudioTrack(int stream, int rate, int channels, int encoding, int buffer, int mode) { nativeCall("create"); requestedBufferBytes = buffer; created.incrementAndGet(); tracks.add(this); }
  public static int getMinBufferSize(int rate, int channels, int encoding) { return 2048; }
  public int getState() { return disposed ? 0 : STATE_INITIALIZED; }
  public int getPlayState() { return playing ? PLAYSTATE_PLAYING : PLAYSTATE_STOPPED; }
  public int getPlaybackHeadPosition() { nativeCall("head"); return played; }
  public void play() { nativeCall("play"); if (framesAtFirstPlay == 0) framesAtFirstPlay = totalWrittenFrames.get(); playing = true; }
  public void pause() {
    nativeCall("pause");
    playing = false; unblockWrite.countDown();
    CountDownLatch entered = pauseEntered, release = pauseRelease;
    if (entered != null && release != null) {
      entered.countDown();
      try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test pause gate timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }
  public void stop() { playing = false; }
  public void flush() { nativeCall("flush"); played = 0; }
  public int write(byte[] value, int offset, int length) {
    nativeCall("blockingWrite");
    if (blockWrites) {
      writeEntered.countDown();
      try { if (!unblockWrite.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test write gate timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      if (!playing) return 0;
    }
    if (writeFailure != 0) return writeFailure;
    int count = Math.min(length, maxWriteBytes);
    if (autoAdvanceHead) played += count / 2;
    totalWrittenFrames.addAndGet(count / 2); return count;
  }
  public int write(byte[] value, int offset, int length, int mode) {
    nativeCall("nonblockingWrite");
    if (mode != WRITE_NON_BLOCKING) throw new IllegalStateException("Expected nonblocking speaker write");
    if (blockWrites) { writeEntered.countDown(); return 0; }
    return write(value, offset, length);
  }
  public int setStereoVolume(float left, float right) { nativeCall("volume"); return 0; }
  public int setVolume(float volume) { return 0; }
  public void release() {
    nativeCall("release");
    if (disposed) throw new IllegalStateException("Double track release");
    disposed = true; released.incrementAndGet();
  }
  public static void advanceAllHeads() { synchronized (tracks) { for (AudioTrack track : tracks) track.played = totalWrittenFrames.get(); } }
  public static void reset() {
    if (created.get() != released.get()) throw new IllegalStateException("Previous test leaked AudioTrack");
    created.set(0); released.set(0); totalWrittenFrames.set(0); tracks.clear();
    blockWrites = false; autoAdvanceHead = true; writeFailure = 0; maxWriteBytes = Integer.MAX_VALUE; requestedBufferBytes = framesAtFirstPlay = 0;
    nativeCalls.clear(); writeEntered = new CountDownLatch(1); pauseEntered = null; pauseRelease = null;
  }
}
