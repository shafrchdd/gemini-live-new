package android.media;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Recorder double tracks exclusivity, leaks, source fallback and read failures. */
public class AudioRecord {
  public static final int STATE_UNINITIALIZED = 0, STATE_INITIALIZED = 1;
  public static final int RECORDSTATE_STOPPED = 1, RECORDSTATE_RECORDING = 3;
  public static final int ERROR = -1, ERROR_BAD_VALUE = -2, ERROR_INVALID_OPERATION = -3, ERROR_DEAD_OBJECT = -6;
  public static final AtomicInteger created = new AtomicInteger(), released = new AtomicInteger();
  public static final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
  public static final List<Integer> sources = Collections.synchronizedList(new ArrayList<Integer>());
  public static volatile int rejectSource = -1, readFailure = 0, minimumBuffer = 2048;
  public static volatile boolean failAllSources, denyStart;
  public final int source;
  private final boolean initialized;
  private volatile boolean recording, disposed;
  public AudioRecord(int source, int rate, int channel, int encoding, int buffer) {
    this.source = source; sources.add(source); created.incrementAndGet();
    initialized = !failAllSources && source != rejectSource;
  }
  public static int getMinBufferSize(int rate, int channel, int encoding) { return minimumBuffer; }
  public int getState() { return initialized && !disposed ? STATE_INITIALIZED : STATE_UNINITIALIZED; }
  public int getRecordingState() { return recording ? RECORDSTATE_RECORDING : RECORDSTATE_STOPPED; }
  public int getAudioSessionId() { return 1; }
  public void startRecording() {
    if (denyStart) throw new SecurityException("Microphone disabled");
    if (!initialized || disposed) throw new IllegalStateException("Recorder not initialized");
    recording = true;
    int count = active.incrementAndGet(), previous;
    do { previous = maxActive.get(); } while (count > previous && !maxActive.compareAndSet(previous, count));
  }
  public int read(byte[] destination, int offset, int length) {
    try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    if (!recording || disposed) return ERROR_INVALID_OPERATION;
    if (readFailure != 0) return readFailure;
    Arrays.fill(destination, offset, offset + length, (byte) 0x12);
    return length;
  }
  public void stop() { if (recording) { recording = false; active.decrementAndGet(); } }
  public void release() {
    if (disposed) throw new IllegalStateException("Double recorder release");
    stop(); disposed = true; released.incrementAndGet();
  }
  public static void reset() {
    if (active.get() != 0) throw new IllegalStateException("Previous test leaked a running recorder");
    created.set(0); released.set(0); maxActive.set(0); sources.clear();
    rejectSource = -1; readFailure = 0; minimumBuffer = 2048; failAllSources = false; denyStart = false;
  }
}
