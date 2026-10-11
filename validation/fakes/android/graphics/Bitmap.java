package android.graphics;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class Bitmap {
  public enum Config { ARGB_8888 }
  public enum CompressFormat { JPEG, PNG, WEBP }
  public static final AtomicInteger created = new AtomicInteger(), recycled = new AtomicInteger();
  public static final List<Integer> qualities = Collections.synchronizedList(new ArrayList<Integer>());
  public static final List<String> compressionThreads = Collections.synchronizedList(new ArrayList<String>());
  public static volatile boolean compressionFails;
  public static volatile int jpegBytes = 1024, compressedWidth, compressedHeight;
  public static volatile int[] jpegBytesByQuality;
  private final int width, height;
  private boolean disposed;
  public Bitmap(int width, int height) { this.width = width; this.height = height; created.incrementAndGet(); }
  public int getWidth() { return width; }
  public int getHeight() { return height; }
  public boolean isRecycled() { return disposed; }
  public void recycle() {
    if (disposed) throw new IllegalStateException("Double bitmap recycle");
    disposed = true; recycled.incrementAndGet();
  }
  public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
    return width == source.width && height == source.height ? source : new Bitmap(width, height);
  }
  public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix matrix, boolean filter) {
    boolean swapped = Math.round(Math.abs(matrix.rotation)) % 180 == 90;
    return new Bitmap(swapped ? height : width, swapped ? width : height);
  }
  public boolean compress(CompressFormat format, int quality, OutputStream output) {
    if (disposed) throw new IllegalStateException("Compressing disposed bitmap");
    if (format != CompressFormat.JPEG) throw new IllegalStateException("Expected metadata-free JPEG normalization");
    qualities.add(quality); compressionThreads.add(Thread.currentThread().getName());
    compressedWidth = width; compressedHeight = height;
    if (compressionFails) return false;
    int length = jpegBytes;
    if (jpegBytesByQuality != null) length = jpegBytesByQuality[quality >= 80 ? 0 : quality >= 60 ? 1 : 2];
    byte[] bytes = new byte[length];
    if (length >= 4) { bytes[0] = (byte)255; bytes[1] = (byte)216; bytes[length - 2] = (byte)255; bytes[length - 1] = (byte)217; }
    try { output.write(bytes); return true; } catch (IOException e) { return false; }
  }
  public static void reset() {
    if (created.get() != recycled.get()) throw new IllegalStateException("Previous test leaked a bitmap");
    created.set(0); recycled.set(0); qualities.clear(); compressionThreads.clear();
    compressionFails = false; jpegBytes = 1024; jpegBytesByQuality = null; compressedWidth = compressedHeight = 0;
  }
}
