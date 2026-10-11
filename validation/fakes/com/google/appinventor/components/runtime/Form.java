package com.google.appinventor.components.runtime;
import java.util.*;
import android.os.Handler;
public class Form extends android.app.Activity {
  public java.io.File imageCacheDir;
  public volatile boolean permissionGranted;
  public volatile int requests, deniedEvents;
  private final List<PermissionResultHandler> callbacks = new ArrayList<PermissionResultHandler>();
  public final List<OnStopListener> stops = new ArrayList<OnStopListener>();
  public final List<OnDestroyListener> destroys = new ArrayList<OnDestroyListener>();
  public boolean isDeniedPermission(String permission) { return !permissionGranted; }
  public void askPermission(String permission, PermissionResultHandler callback) {
    if (permissionGranted) callback.HandlePermissionResponse(permission, true);
    else { synchronized (callbacks) { requests++; callbacks.add(callback); } }
  }
  public void respond(final boolean granted) throws Exception {
    final PermissionResultHandler callback;
    synchronized (callbacks) { callback = callbacks.remove(0); }
    permissionGranted = granted;
    Handler.runSync(new Runnable() { public void run() {
      callback.HandlePermissionResponse("android.permission.RECORD_AUDIO", granted);
    }});
  }
  public void dispatchPermissionDeniedEvent(Component component, String function, String permission) { deniedEvents++; }
  public void registerForOnStop(OnStopListener listener) { stops.add(listener); }
  public void registerForOnDestroy(OnDestroyListener listener) { destroys.add(listener); }
  public java.io.File getCacheDir() {
    if (imageCacheDir == null) {
      imageCacheDir = new java.io.File(System.getProperty("java.io.tmpdir"), "gemini-image-test-" + System.nanoTime());
      if (!imageCacheDir.mkdirs()) throw new IllegalStateException("Cannot create image test cache");
    }
    return imageCacheDir;
  }
}
