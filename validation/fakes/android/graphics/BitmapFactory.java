package android.graphics;
public final class BitmapFactory {
  public static final class Options {
    public boolean inJustDecodeBounds, inScaled = true;
    public int outWidth, outHeight, inSampleSize = 1;
    public String outMimeType;
    public Bitmap.Config inPreferredConfig;
  }
  public static volatile int sourceWidth = 4000, sourceHeight = 3000, sampleSize;
  public static volatile boolean badBounds, decodeFails, outOfMemory;
  public static Bitmap decodeFile(String path, Options options) {
    if (options.inJustDecodeBounds) {
      options.outWidth = badBounds ? -1 : sourceWidth;
      options.outHeight = badBounds ? -1 : sourceHeight;
      options.outMimeType = badBounds ? null : "image/jpeg";
      return null;
    }
    if (outOfMemory) throw new OutOfMemoryError("Simulated device image memory limit");
    if (decodeFails) return null;
    sampleSize = Math.max(1, options.inSampleSize);
    return new Bitmap(Math.max(1, sourceWidth / sampleSize), Math.max(1, sourceHeight / sampleSize));
  }
  public static void reset() {
    sourceWidth = 4000; sourceHeight = 3000; sampleSize = 0;
    badBounds = decodeFails = outOfMemory = false;
  }
}
