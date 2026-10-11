package android.media.audiofx;
import java.util.concurrent.atomic.*;
public final class NoiseSuppressor {
  public static volatile boolean available = true, fail;
  public static final AtomicInteger created = new AtomicInteger(), released = new AtomicInteger();
  private boolean disposed;
  public static boolean isAvailable() { return available; }
  public static NoiseSuppressor create(int session) {
    if (fail) throw new IllegalArgumentException("Unsupported effect");
    created.incrementAndGet(); return new NoiseSuppressor();
  }
  public int setEnabled(boolean enabled) { return 0; }
  public void release() {
    if (disposed) throw new IllegalStateException("Double effect release");
    disposed = true; released.incrementAndGet();
  }
}
