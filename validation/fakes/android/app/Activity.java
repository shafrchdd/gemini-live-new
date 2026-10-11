package android.app;
public class Activity extends android.content.Context {
  public void runOnUiThread(Runnable runnable) { new android.os.Handler().post(runnable); }
}
