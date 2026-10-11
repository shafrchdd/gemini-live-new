package android.content;
public class Context {
  public static final String AUDIO_SERVICE = "audio";
  public Object getSystemService(String name) { return new android.media.AudioManager(); }
  public int checkSelfPermission(String permission) { return 0; }
  public int checkCallingOrSelfPermission(String permission) { return 0; }
}
