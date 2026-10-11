package android.media;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.*;
public class AudioTrack {
  public static final int MODE_STREAM = 1, STATE_INITIALIZED = 1, PLAYSTATE_STOPPED = 1, PLAYSTATE_PLAYING = 3;
  public static final AtomicInteger created = new AtomicInteger(), released = new AtomicInteger();
  public static final AtomicInteger totalWrittenFrames = new AtomicInteger();
  public static volatile boolean blockWrites, autoAdvanceHead = true;
  public static volatile int writeFailure;
  public static volatile CountDownLatch writeEntered = new CountDownLatch(1), pauseEntered, pauseRelease;
  private static final List<AudioTrack> tracks = Collections.synchronizedList(new ArrayList<AudioTrack>());
  private final CountDownLatch unblockWrite = new CountDownLatch(1);
  private volatile boolean disposed, playing;
  private int played;
  public AudioTrack(int stream, int rate, int channels, int encoding, int buffer, int mode) { created.incrementAndGet(); tracks.add(this); }
  public static int getMinBufferSize(int rate, int channels, int encoding) { return 2048; }
  public int getState() { return disposed ? 0 : STATE_INITIALIZED; }
  public int getPlayState() { return playing ? PLAYSTATE_PLAYING : PLAYSTATE_STOPPED; }
  public int getPlaybackHeadPosition() { return played; }
  public void play() { playing = true; }
  public void pause() {
    playing = false; unblockWrite.countDown();
    CountDownLatch entered = pauseEntered, release = pauseRelease;
    if (entered != null && release != null) {
      entered.countDown();
      try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test pause gate timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }
  public void stop() { playing = false; }
  public void flush() { played = 0; }
  public int write(byte[] value, int offset, int length) {
    if (blockWrites) {
      writeEntered.countDown();
      try { if (!unblockWrite.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test write gate timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      if (!playing) return 0;
    }
    if (writeFailure != 0) return writeFailure;
    if (autoAdvanceHead) played += length / 2;
    totalWrittenFrames.addAndGet(length / 2); return length;
  }
  public int setStereoVolume(float left, float right) { return 0; }
  public int setVolume(float volume) { return 0; }
  public void release() {
    if (disposed) throw new IllegalStateException("Double track release");
    disposed = true; released.incrementAndGet();
  }
  public static void advanceAllHeads() { synchronized (tracks) { for (AudioTrack track : tracks) track.played = totalWrittenFrames.get(); } }
  public static void reset() {
    if (created.get() != released.get()) throw new IllegalStateException("Previous test leaked AudioTrack");
    created.set(0); released.set(0); totalWrittenFrames.set(0); tracks.clear();
    blockWrites = false; autoAdvanceHead = true; writeFailure = 0; writeEntered = new CountDownLatch(1); pauseEntered = null; pauseRelease = null;
  }
}
