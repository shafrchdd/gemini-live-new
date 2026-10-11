package android.media;
import java.io.*;
public final class ExifInterface {
  public static final String TAG_ORIENTATION = "Orientation";
  public static final int ORIENTATION_UNDEFINED = 0, ORIENTATION_NORMAL = 1, ORIENTATION_FLIP_HORIZONTAL = 2,
      ORIENTATION_ROTATE_180 = 3, ORIENTATION_FLIP_VERTICAL = 4, ORIENTATION_TRANSPOSE = 5,
      ORIENTATION_ROTATE_90 = 6, ORIENTATION_TRANSVERSE = 7, ORIENTATION_ROTATE_270 = 8;
  public static volatile int orientation = ORIENTATION_NORMAL;
  public static volatile boolean fails;
  public ExifInterface(String path) throws IOException { if (fails) throw new IOException("No EXIF metadata"); }
  public int getAttributeInt(String name, int fallback) { return orientation; }
}
