package com.fabiyon.geminilive;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ExifInterface;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.util.MediaUtil;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

@DesignerComponent(version=4, description="Direct Gemini Live client with microphone streaming, photo and text input, safe session cleanup, and bounded media queues.", category=ComponentCategory.EXTENSION, nonVisible=true, iconName="")
@SimpleObject(external=true)
@UsesPermissions(permissionNames="android.permission.INTERNET,android.permission.RECORD_AUDIO")
public class GeminiLive extends AndroidNonvisibleComponent implements OnStopListener, OnDestroyListener, Deleteable {
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final int INPUT_RATE = 16000;
  private static final int MIC_CHUNK_BYTES = 640; // 20 ms, mono PCM16.
  private static final int MAX_PENDING_AUDIO = 8; // At most 160 ms of unsent capture.
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Object stateLock = new Object();
  private final Object functionsLock = new Object();
  private final LinkedHashMap<String, JSONObject> functions = new LinkedHashMap<String, JSONObject>();
  private final ThreadPoolExecutor microphoneWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<Runnable>(2), threadFactory("GeminiLive-microphone"));
  private final ThreadPoolExecutor catalogWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<Runnable>(4), threadFactory("GeminiLive-catalog"));
  private final ThreadPoolExecutor imageWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<Runnable>(1), threadFactory("GeminiLive-image"));
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(threadFactory("GeminiLive-watchdog"));
  private volatile Session session;
  private volatile MicRun microphone;
  private volatile boolean destroyed;
  private boolean listenRequested;
  private long listenGeneration;
  private volatile long connectionGeneration;
  private final long[] catalogGeneration = new long[2];
  private volatile String model = "gemini-3.8-live";
  private volatile String voice = "Puck";
  private volatile String systemInstruction = "";
  private volatile boolean echoCancellation = true;
  private volatile boolean noiseSuppression = true;
  private volatile boolean muteMicWhileSpeaking;
  private volatile boolean audioEnabled = true;
  private volatile boolean rawMessagesEnabled;
  private volatile float volume = 1f;

  public GeminiLive(ComponentContainer container) {
    super(container.$form());
    form.registerForOnStop(this);
    form.registerForOnDestroy(this);
    watchdog.scheduleWithFixedDelay(new Runnable() {
      public void run() { checkTimeouts(); }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private static ThreadFactory threadFactory(final String name) {
    return new ThreadFactory() {
      public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
      }
    };
  }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Live model used for the next connection. Availability depends on the API key.")
  public String Model() { return model; }
  @DesignerProperty(editorType="string", defaultValue="gemini-3.8-live")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Model(String value) {
    if (value != null && !value.trim().isEmpty()) {
      String name = value.trim();
      model = name.startsWith("models/") ? name.substring(7) : name;
    }
  }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Prebuilt voice ID used for the next connection.")
  public String Voice() { return voice; }
  @DesignerProperty(editorType="string", defaultValue="Puck")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Voice(String value) { if (value != null && !value.trim().isEmpty()) voice = value.trim(); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="System instruction for the next connection.")
  public String SystemInstruction() { return systemInstruction; }
  @DesignerProperty(editorType="textArea", defaultValue="")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void SystemInstruction(String value) { systemInstruction = value == null ? "" : value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Enable device acoustic echo cancellation for the next microphone start, when supported.")
  public boolean EchoCancellation() { return echoCancellation; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void EchoCancellation(boolean value) { echoCancellation = value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Enable device noise suppression for the next microphone start, when supported.")
  public boolean NoiseSuppression() { return noiseSuppression; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void NoiseSuppression(boolean value) { noiseSuppression = value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Discard microphone audio while queued or buffered assistant audio is playing. Disables voice barge-in.")
  public boolean MuteMicWhileSpeaking() { return muteMicWhileSpeaking; }
  @DesignerProperty(editorType="boolean", defaultValue="False")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void MuteMicWhileSpeaking(boolean value) { muteMicWhileSpeaking = value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Dispatch RawMessage, including audio Base64. Disable when unused to reduce UI work.")
  public boolean RawMessagesEnabled() { return rawMessagesEnabled; }
  @DesignerProperty(editorType="boolean", defaultValue="False")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void RawMessagesEnabled(boolean value) { rawMessagesEnabled = value; }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Play assistant PCM audio.")
  public boolean AudioEnabled() { return audioEnabled; }
  @DesignerProperty(editorType="boolean", defaultValue="True")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void AudioEnabled(boolean value) { audioEnabled = value; if (!value) StopAudio(); }

  @SimpleProperty(category=PropertyCategory.BEHAVIOR, description="Playback volume, 0 to 100.")
  public int Volume() { return Math.round(volume * 100f); }
  @DesignerProperty(editorType="integer", defaultValue="100")
  @SimpleProperty(category=PropertyCategory.BEHAVIOR)
  public void Volume(int value) {
    volume = Math.max(0, Math.min(100, value)) / 100f;
    Session current = session;
    if (current != null) current.playback.updateVolume();
  }

  @SimpleFunction public boolean IsEchoCancellationAvailable() { return AcousticEchoCanceler.isAvailable(); }
  @SimpleFunction public boolean IsNoiseSuppressionAvailable() { return NoiseSuppressor.isAvailable(); }
  @SimpleFunction public boolean IsConnected() { Session s = session; return current(s) && s.connected; }
  @SimpleFunction public boolean IsReady() { return ready(session); }
  @SimpleFunction(description="Whether a photo is being prepared or sent in the current session.")
  public boolean IsSendingImage() { Session s = session; return ready(s) && s.imagePending.get(); }
  @SimpleFunction public boolean IsListening() {
    MicRun mic = microphone;
    return mic != null && mic.started && !mic.cancelled && ready(mic.owner);
  }
  @SimpleFunction(description="Latest captured microphone peak level, 0 to 100. Zero can indicate silence or Android microphone privacy blocking.")
  public int MicrophoneLevel() { MicRun mic = microphone; return mic == null ? 0 : mic.level; }
  @SimpleFunction(description="Assistant audio awaiting speaker writes, in milliseconds. Excludes Android hardware and network buffering; does not call native audio from the UI thread.")
  public int PlaybackBufferedMilliseconds() { Session s = session; return current(s) ? s.playback.bufferedMilliseconds() : 0; }

  @SimpleFunction(description="Clear functions for the next connection.")
  public void ClearFunctions() { synchronized (functionsLock) { functions.clear(); } }

  @SimpleFunction(description="Add or replace a named function for the next connection. parametersJson is a JSON Schema object.")
  public void AddFunction(String name, String description, String parametersJson) {
    try {
      if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Function name is empty");
      JSONObject declaration = new JSONObject().put("name", name.trim()).put("description", description == null ? "" : description);
      if (parametersJson != null && !parametersJson.trim().isEmpty()) declaration.put("parameters", new JSONObject(parametersJson));
      synchronized (functionsLock) { functions.put(name.trim(), declaration); }
    } catch (Exception e) { Error("AddFunction: " + detail(e, null)); }
  }

  @SimpleFunction(description="Connect with an API key. Wait for SetupComplete before sending or starting the microphone.")
  public void Connect(final String apiKey) {
    if (destroyed) { Error("This extension has been destroyed."); return; }
    if (apiKey == null || apiKey.trim().isEmpty()) { Error("API key is empty."); return; }
    final String setup;
    try { setup = makeSetup(); }
    catch (Exception e) { Error("Setup: " + detail(e, null)); return; }
    final Session next = new Session(apiKey.trim());
    Session previous;
    synchronized (stateLock) {
      if (destroyed) { next.writer.shutdownNow(); return; }
      previous = session;
      next.generation = ++connectionGeneration;
      session = next;
      stopMicLocked();
    }
    closeSession(previous, "Replaced by a new connection");
    next.playback.start();
    new Thread(new Runnable() {
      public void run() {
        try {
          next.transport.connect(next.key);
          if (!current(next)) return;
          next.setupStarted = System.nanoTime();
          next.connected = true;
          post(next, new Runnable() { public void run() { Connected(); } });
          writeNow(next, setup);
          post(next, new Runnable() { public void run() { SetupSent(setup); } });
          while (current(next)) {
            String message = next.transport.readMessage();
            if (message == null) break;
            handleMessage(next, message);
          }
          closeSession(next, next.transport.getCloseReason());
        } catch (Exception e) {
          if (current(next)) Error("Connection: " + detail(e, next.key));
          closeSession(next, "Connection closed");
        } finally {
          closeSession(next, next.transport.getCloseReason());
        }
      }
    }, "GeminiLive-connection").start();
  }

  @SimpleFunction public void Disconnect() {
    Session previous;
    synchronized (stateLock) {
      previous = session;
      session = null;
      stopMicLocked();
    }
    closeSession(previous, "Disconnected by app");
  }

  @SimpleFunction(description="Send a complete user text turn after SetupComplete.")
  public void SendText(String text) {
    Session s = session;
    if (!ready(s)) { Error("Gemini is not ready. Wait for SetupComplete."); return; }
    if (text == null || text.trim().isEmpty()) { Error("Text is empty."); return; }
    try {
      JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)));
      send(s, new JSONObject().put("clientContent", new JSONObject().put("turns", new JSONArray().put(turn)).put("turnComplete", true)).toString(), false);
    } catch (Exception e) { Error("SendText: " + detail(e, s.key)); }
  }

  @SimpleFunction(description="Send a camera/picker photo or local image with a text prompt after SetupComplete. Accepts assets, accessible local paths, file URIs and granted content URIs. Resizes to JPEG up to 1024 pixels, off the UI thread. One image can be pending at a time. ImageSent confirms network writes, not model acknowledgment.")
  public void SendImage(final String imagePath, final String text) {
    final Session owner = session;
    if (!ready(owner)) { Error("Gemini is not ready. Wait for SetupComplete."); return; }
    if (imagePath == null || imagePath.trim().isEmpty()) { Error("SendImage: Select or capture an image first."); return; }
    if (text == null || text.trim().isEmpty()) { Error("SendImage: Enter a text prompt for the image."); return; }
    if (text.length() > 16384) { Error("SendImage: The prompt exceeds 16384 characters."); return; }
    final long generation;
    synchronized (stateLock) {
      if (!ready(owner)) return;
      if (!owner.imagePending.compareAndSet(false, true)) { Error("SendImage: An image is already being prepared or sent."); return; }
      generation = ++owner.imageGeneration;
    }
    try {
      Future<?> task = imageWorker.submit(new Runnable() {
        public void run() { prepareAndSendImage(owner, generation, imagePath, text); }
      });
      synchronized (stateLock) {
        if (ready(owner) && owner.imageGeneration == generation && owner.imagePending.get()) owner.imageFuture = task;
        else task.cancel(true);
      }
    } catch (RejectedExecutionException e) {
      finishImage(owner, generation);
      if (current(owner)) Error("SendImage: The image worker is busy. Please retry.");
    }
  }

  private void prepareAndSendImage(final Session owner, final long generation, final String path, final String text) {
    boolean queued = false;
    String failure = null;
    try {
      if (!ready(owner) || Thread.currentThread().isInterrupted()) return;
      byte[] jpeg = prepareImage(path, owner);
      if (!ready(owner) || Thread.currentThread().isInterrupted()) return;
      JSONObject video = new JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
      final String imagePayload = new JSONObject().put("realtimeInput", new JSONObject().put("video", video)).toString();
      final String textPayload = new JSONObject().put("realtimeInput", new JSONObject().put("text", text)).toString();
      owner.writer.execute(new Runnable() {
        public void run() {
          String writeFailure = null;
          boolean written = false;
          try {
            if (!ready(owner)) return;
            // Keep the frame and its prompt consecutive on the shared writer.
            writeNow(owner, imagePayload);
            if (!ready(owner)) return;
            writeNow(owner, textPayload);
            written = true;
          } catch (Exception e) { writeFailure = detail(e, owner.key); }
          finally { finishImage(owner, generation); }
          if (written) post(owner, new Runnable() { public void run() { ImageSent(path); } });
          if (writeFailure != null && current(owner)) {
            Error("SendImage: " + writeFailure);
            closeSession(owner, "Image send failed");
          }
        }
      });
      queued = true;
    } catch (RejectedExecutionException e) { failure = "Outgoing queue is full. Please retry the image."; }
    catch (OutOfMemoryError e) { failure = "Not enough memory to prepare this image. Use a smaller photo."; }
    catch (Exception e) { failure = detail(e, owner.key); }
    finally { if (!queued) finishImage(owner, generation); }
    if (failure != null && current(owner)) Error("SendImage: " + failure);
  }

  private static final int IMAGE_SOURCE_LIMIT = 20 * 1024 * 1024;
  private static final int IMAGE_JPEG_LIMIT = 512 * 1024;
  private static final int IMAGE_DECODE_DIMENSION = 2048;
  private static final int IMAGE_SEND_DIMENSION = 1024;

  /** Called by the image worker; never decode a full-resolution photo or load its source into heap. */
  private byte[] prepareImage(String imagePath, Session owner) throws java.io.IOException {
    if (imagePath == null || imagePath.trim().length() == 0) throw new java.io.IOException("Image path is empty.");
    String path = imagePath.trim();
    int colon = path.indexOf(':');
    String scheme = colon > 0 && path.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")
        ? path.substring(0, colon) : null;
    if (scheme != null && !"file".equalsIgnoreCase(scheme) && !"content".equalsIgnoreCase(scheme)) {
      throw new java.io.IOException("Choose an asset, local file, or content URI; remote URLs and data URIs are not supported.");
    }
    if (scheme != null) path = scheme.toLowerCase(java.util.Locale.US) + path.substring(scheme.length());
    checkImageOwner(owner);
    java.io.File temporary = java.io.File.createTempFile("gemini-live-image-", ".tmp", form.getCacheDir());
    android.graphics.Bitmap bitmap = null;
    try {
      spoolImage(path, temporary, owner);
      checkImageOwner(owner);
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeFile(temporary.getAbsolutePath(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("Image format could not be decoded.");
      int sample = 1;
      while (((long) bounds.outWidth + sample - 1) / sample > IMAGE_DECODE_DIMENSION
          || ((long) bounds.outHeight + sample - 1) / sample > IMAGE_DECODE_DIMENSION) {
        sample *= 2;
      }
      int orientation = readImageOrientation(temporary.getAbsolutePath());
      checkImageOwner(owner);
      android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
      options.inSampleSize = sample;
      options.inScaled = false;
      options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
      bitmap = android.graphics.BitmapFactory.decodeFile(temporary.getAbsolutePath(), options);
      if (bitmap == null) throw new java.io.IOException("Image format could not be decoded.");
      if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
          || bitmap.getWidth() > IMAGE_DECODE_DIMENSION || bitmap.getHeight() > IMAGE_DECODE_DIMENSION) {
        throw new java.io.IOException("Image decoder returned invalid or unexpectedly large dimensions.");
      }
      checkImageOwner(owner);
      if (bitmap.getWidth() > IMAGE_SEND_DIMENSION || bitmap.getHeight() > IMAGE_SEND_DIMENSION) {
        float ratio = Math.min((float) IMAGE_SEND_DIMENSION / bitmap.getWidth(),
            (float) IMAGE_SEND_DIMENSION / bitmap.getHeight());
        int width = Math.max(1, Math.min(IMAGE_SEND_DIMENSION, Math.round(bitmap.getWidth() * ratio)));
        int height = Math.max(1, Math.min(IMAGE_SEND_DIMENSION, Math.round(bitmap.getHeight() * ratio)));
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (scaled != bitmap) {
          android.graphics.Bitmap original = bitmap;
          bitmap = scaled;
          original.recycle();
        }
      }
      checkImageOwner(owner);
      if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL) {
        android.graphics.Matrix matrix = imageOrientationMatrix(orientation);
        if (matrix != null) {
          android.graphics.Bitmap oriented = android.graphics.Bitmap.createBitmap(bitmap, 0, 0,
              bitmap.getWidth(), bitmap.getHeight(), matrix, true);
          if (oriented != bitmap) {
            android.graphics.Bitmap original = bitmap;
            bitmap = oriented;
            original.recycle();
          }
        }
      }
      int[] qualities = new int[] {80, 60, 40};
      for (int quality : qualities) {
        checkImageOwner(owner);
        ImageByteBuffer jpeg = new ImageByteBuffer(IMAGE_JPEG_LIMIT);
        boolean success;
        try {
          success = encodeImageJpeg(bitmap, quality, jpeg);
        } catch (java.io.IOException failure) {
          if (!jpeg.limitExceeded) throw failure;
          success = false;
        }
        if (success && !jpeg.limitExceeded) {
          checkImageOwner(owner);
          if (jpeg.size() == 0) throw new java.io.IOException("Image JPEG encoding returned no data.");
          return jpeg.toByteArray();
        }
        if (!jpeg.limitExceeded) throw new java.io.IOException("Image JPEG encoding failed.");
      }
      throw new java.io.IOException("Image remains larger than 512 KiB after JPEG compression. Choose a simpler or smaller image.");
    } finally {
      if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
      // The file is app-private and no longer needed on success, cancellation, or failure.
      temporary.delete();
    }
  }

  private void checkImageOwner(Session owner) throws java.io.InterruptedIOException {
    if (Thread.currentThread().isInterrupted() || !ready(owner)) {
      throw new java.io.InterruptedIOException("Image preparation was cancelled.");
    }
  }

  private void spoolImage(String path, java.io.File temporary, Session owner) throws java.io.IOException {
    java.io.InputStream source = null;
    java.io.OutputStream destination = null;
    try {
      checkImageOwner(owner);
      source = com.google.appinventor.components.runtime.util.MediaUtil.openMedia(form, path);
      if (source == null) throw new java.io.IOException("Image source could not be opened.");
      synchronized (stateLock) {
        checkImageOwner(owner);
        owner.imageInput = source;
      }
      destination = new java.io.FileOutputStream(temporary);
      byte[] block = new byte[8192];
      int total = 0;
      for (;;) {
        checkImageOwner(owner);
        int count = source.read(block);
        if (count < 0) break;
        if (count == 0) {
          int next = source.read();
          if (next < 0) break;
          block[0] = (byte) next;
          count = 1;
        }
        if (count > IMAGE_SOURCE_LIMIT - total) throw new java.io.IOException("Image source exceeds the 20 MiB limit.");
        destination.write(block, 0, count);
        total += count;
      }
      if (total == 0) throw new java.io.IOException("Image source is empty.");
    } finally {
      synchronized (stateLock) {
        if (owner.imageInput == source) owner.imageInput = null;
      }
      try {
        if (destination != null) destination.close();
      } finally {
        if (source != null) {
          try { source.close(); } catch (java.io.IOException ignored) { /* Read is finished; no further source access is possible. */ }
        }
      }
    }
  }

  private static int readImageOrientation(String path) {
    try {
      int value = new android.media.ExifInterface(path).getAttributeInt(
          android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
      return value >= 1 && value <= 8 ? value : android.media.ExifInterface.ORIENTATION_NORMAL;
    } catch (java.io.IOException ignored) {
      // Formats without EXIF can still be valid images; their pixels use normal orientation.
      return android.media.ExifInterface.ORIENTATION_NORMAL;
    }
  }

  private static android.graphics.Matrix imageOrientationMatrix(int orientation) {
    android.graphics.Matrix matrix = new android.graphics.Matrix();
    switch (orientation) {
      case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
      case android.media.ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
      case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
      case android.media.ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
      case android.media.ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
      case android.media.ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
      case android.media.ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
      default: return null;
    }
    return matrix;
  }

  private static boolean encodeImageJpeg(android.graphics.Bitmap bitmap, int quality, ImageByteBuffer output)
      throws java.io.IOException {
    // Android's native encoder can propagate an IOException thrown by OutputStream.write.
    return bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output);
  }

  /** Unlike ByteArrayOutputStream, allocation itself never grows beyond the JPEG byte budget. */
  private static final class ImageByteBuffer extends java.io.OutputStream {
    private final int limit;
    private byte[] bytes;
    private int count;
    boolean limitExceeded;
    ImageByteBuffer(int limit) { this.limit = limit; bytes = new byte[Math.min(8192, limit)]; }
    public void write(int value) throws java.io.IOException {
      reserve(1);
      bytes[count++] = (byte) value;
    }
    public void write(byte[] source, int offset, int length) throws java.io.IOException {
      if (source == null) throw new NullPointerException();
      if (offset < 0 || length < 0 || offset > source.length - length) throw new IndexOutOfBoundsException();
      reserve(length);
      System.arraycopy(source, offset, bytes, count, length);
      count += length;
    }
    private void reserve(int length) throws java.io.IOException {
      if (length > limit - count) {
        limitExceeded = true;
        throw new java.io.IOException("Encoded JPEG exceeds the byte budget.");
      }
      if (count + length > bytes.length) {
        int capacity = Math.min(limit, Math.max(count + length, bytes.length * 2));
        byte[] replacement = new byte[capacity];
        System.arraycopy(bytes, 0, replacement, 0, count);
        bytes = replacement;
      }
    }
    int size() { return count; }
    byte[] toByteArray() {
      if (count == bytes.length) return bytes;
      byte[] result = new byte[count];
      System.arraycopy(bytes, 0, result, 0, count);
      return result;
    }
  }

  private void finishImage(Session owner, long generation) {
    synchronized (stateLock) {
      if (owner.imageGeneration == generation) { owner.imagePending.set(false); owner.imageFuture = null; }
    }
  }

  @SimpleFunction(description="Return a function result to the current session. Cancelled or already answered call IDs are rejected.")
  public void SendFunctionResult(String callId, String name, String resultJson) {
    final Session s = session;
    if (!ready(s)) { Error("Gemini is not ready."); return; }
    try {
      final String id = callId == null ? "" : callId;
      if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Function name is empty");
      if (id.isEmpty()) throw new IllegalArgumentException("Function call ID is empty");
      final PendingCall pending = s.calls.get(id);
      if (pending == null || !name.equals(pending.name)) throw new IllegalArgumentException("Unknown, cancelled, or mismatched function call ID");
      JSONObject response;
      try { response = new JSONObject(resultJson == null ? "{}" : resultJson); }
      catch (Exception ignored) { response = new JSONObject().put("result", resultJson == null ? "" : resultJson); }
      JSONObject result = new JSONObject().put("name", name).put("response", response);
      result.put("id", id);
      final String payload = new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(result))).toString();
      if (!pending.responseQueued.compareAndSet(false, true)) throw new IllegalArgumentException("A response is already queued for this function call");
      try {
        s.writer.execute(new Runnable() {
          public void run() {
            try {
              if (ready(s) && s.calls.get(id) == pending) {
                writeNow(s, payload);
                s.calls.remove(id, pending);
              }
            } catch (Exception e) {
              if (current(s)) Error("Function result: " + detail(e, s.key));
              closeSession(s, "Function result send failed");
            }
          }
        });
      } catch (RejectedExecutionException e) {
        pending.responseQueued.set(false);
        if (current(s)) Error("Outgoing queue is full. Please retry the function result.");
      }
    } catch (Exception e) { Error("SendFunctionResult: " + detail(e, s.key)); }
  }

  @SimpleFunction(description="Request microphone permission and stream 16 kHz mono PCM16. Call after SetupComplete. Stops when this screen goes to the background.")
  public void StartListening() {
    final Session owner = session;
    if (!ready(owner)) { Error("Gemini is not ready. Wait for SetupComplete."); return; }
    final long generation;
    synchronized (stateLock) {
      if (listenRequested) return;
      if (!ready(owner)) return;
      listenRequested = true;
      generation = ++listenGeneration;
    }
    ui.post(new Runnable() {
      public void run() {
        if (!wantMic(owner, generation)) return;
        try {
          form.askPermission("android.permission.RECORD_AUDIO", new PermissionResultHandler() {
            public void HandlePermissionResponse(String permission, boolean granted) {
              if (!wantMic(owner, generation)) return;
              if (!granted) {
                synchronized (stateLock) { if (generation == listenGeneration) listenRequested = false; }
                form.dispatchPermissionDeniedEvent(GeminiLive.this, "StartListening", permission);
                Error("Microphone permission denied. Allow microphone access for this APK in Android app settings.");
                return;
              }
              final MicRun run;
              synchronized (stateLock) {
                if (!wantMic(owner, generation)) return;
                run = new MicRun(owner, generation);
                microphone = run;
              }
              try { microphoneWorker.execute(run); }
              catch (RejectedExecutionException e) {
                synchronized (stateLock) {
                  if (microphone == run) { microphone = null; listenRequested = false; }
                }
                if (!destroyed) Error("Microphone worker is unavailable.");
              }
            }
          });
        } catch (Exception e) {
          synchronized (stateLock) { if (generation == listenGeneration) listenRequested = false; }
          Error("Microphone permission: " + detail(e, owner.key));
        }
      }
    });
  }

  @SimpleFunction(description="Cancel pending permission/start requests, stop capture, release its resources, and then send audioStreamEnd.")
  public void StopListening() { synchronized (stateLock) { stopMicLocked(); } }

  @SimpleFunction(description="Request stop and flush of buffered assistant audio. Returns without waiting for native speaker cleanup on the playback worker.")
  public void StopAudio() { Session s = session; if (s != null) s.playback.flush(); }

  @SimpleFunction(description="Fetch compatible Live models, including paginated native-audio models.")
  public void RefreshModels(String key) { fetchCatalog(key, true); }
  @SimpleFunction(description="Fetch prebuilt voice IDs usable by Voice. Excludes custom voices.")
  public void RefreshVoices(String key) { fetchCatalog(key, false); }

  @SimpleEvent public void ModelsLoaded(YailList models) { EventDispatcher.dispatchEvent(this, "ModelsLoaded", models); }
  @SimpleEvent public void VoicesLoaded(YailList voices) { EventDispatcher.dispatchEvent(this, "VoicesLoaded", voices); }
  @SimpleEvent public void FunctionCall(String name, String argumentsJson, String callId) { EventDispatcher.dispatchEvent(this, "FunctionCall", name, argumentsJson, callId); }
  @SimpleEvent public void FunctionCallCancelled(String callId) { EventDispatcher.dispatchEvent(this, "FunctionCallCancelled", callId); }
  @SimpleEvent public void ListeningStarted() { EventDispatcher.dispatchEvent(this, "ListeningStarted"); }
  @SimpleEvent public void ListeningStopped() { EventDispatcher.dispatchEvent(this, "ListeningStopped"); }
  @SimpleEvent public void InputTranscription(String text) { EventDispatcher.dispatchEvent(this, "InputTranscription", text); }
  @SimpleEvent public void OutputTranscription(String text) { EventDispatcher.dispatchEvent(this, "OutputTranscription", text); }
  @SimpleEvent public void TurnComplete() { EventDispatcher.dispatchEvent(this, "TurnComplete"); }
  @SimpleEvent public void Connected() { EventDispatcher.dispatchEvent(this, "Connected"); }
  @SimpleEvent public void SetupSent(String json) { EventDispatcher.dispatchEvent(this, "SetupSent", json); }
  @SimpleEvent public void SetupComplete() { EventDispatcher.dispatchEvent(this, "SetupComplete"); }
  @SimpleEvent public void RawMessage(String message) { EventDispatcher.dispatchEvent(this, "RawMessage", message); }
  @SimpleEvent public void TextReceived(String text) { EventDispatcher.dispatchEvent(this, "TextReceived", text); }
  @SimpleEvent(description="The image frame and its text prompt were written to the current connection. This is not server acknowledgment.")
  public void ImageSent(String imagePath) { EventDispatcher.dispatchEvent(this, "ImageSent", imagePath); }
  @SimpleEvent public void Disconnected(String reason) { EventDispatcher.dispatchEvent(this, "Disconnected", reason); }
  @SimpleEvent public void Error(final String message) {
    ui.post(new Runnable() { public void run() { if (!destroyed) EventDispatcher.dispatchEvent(GeminiLive.this, "Error", message); } });
  }

  public void onStop() { Disconnect(); }
  public void onDestroy() { dispose(); }
  public void onDelete() { dispose(); }
  private void dispose() {
    synchronized (stateLock) { if (destroyed) return; destroyed = true; }
    Disconnect();
    microphoneWorker.shutdownNow();
    catalogWorker.shutdownNow();
    imageWorker.shutdownNow();
    watchdog.shutdownNow();
    ui.removeCallbacksAndMessages(null);
  }

  private boolean current(Session s) { return s != null && session == s && !s.closed.get() && !destroyed; }
  private boolean ready(Session s) { return current(s) && s.ready; }
  private boolean wantMic(Session s, long generation) {
    synchronized (stateLock) { return ready(s) && listenRequested && listenGeneration == generation; }
  }
  private void post(final Session owner, final Runnable event) {
    ui.post(new Runnable() { public void run() { if (current(owner)) event.run(); } });
  }

  private JSONObject declarationSnapshot() throws Exception {
    JSONArray declarations = new JSONArray();
    synchronized (functionsLock) {
      for (JSONObject declaration : functions.values()) declarations.put(new JSONObject(declaration.toString()));
    }
    return new JSONObject().put("functionDeclarations", declarations);
  }
  private String makeSetup() throws Exception {
    JSONObject speech = new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)));
    JSONObject body = new JSONObject().put("model", "models/" + model)
        .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("AUDIO")).put("speechConfig", speech))
        .put("inputAudioTranscription", new JSONObject()).put("outputAudioTranscription", new JSONObject());
    String instruction = systemInstruction;
    if (!instruction.trim().isEmpty()) body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instruction))));
    JSONObject tool = declarationSnapshot();
    if (tool.getJSONArray("functionDeclarations").length() > 0) body.put("tools", new JSONArray().put(tool));
    return new JSONObject().put("setup", body).toString();
  }

  private static final class PendingCall {
    final String name;
    final AtomicBoolean responseQueued = new AtomicBoolean();
    PendingCall(String name) { this.name = name; }
  }

  private final class Session {
    final String key;
    final Transport transport = new Transport();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicInteger pendingAudio = new AtomicInteger();
    final AtomicInteger pendingRawEvents = new AtomicInteger();
    final AtomicBoolean rawOverflowReported = new AtomicBoolean();
    final TextEvents textEvents = new TextEvents(this);
    final AtomicBoolean imagePending = new AtomicBoolean();
    long imageGeneration;
    Future<?> imageFuture;
    InputStream imageInput;
    final ConcurrentHashMap<String, PendingCall> calls = new ConcurrentHashMap<String, PendingCall>();
    final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(32), threadFactory("GeminiLive-writer"));
    final Playback playback;
    final long connectStarted = System.nanoTime();
    volatile long setupStarted;
    volatile long writeStarted;
    volatile boolean connected;
    volatile boolean ready;
    volatile long generation;
    Session(String key) { this.key = key; playback = new Playback(this); }
  }

  private boolean send(final Session s, final String payload, final boolean audio) {
    if (!ready(s)) return false;
    if (audio && s.pendingAudio.incrementAndGet() > MAX_PENDING_AUDIO) { s.pendingAudio.decrementAndGet(); return false; }
    try {
      s.writer.execute(new Runnable() {
        public void run() {
          try { if (ready(s)) writeNow(s, payload); }
          catch (Exception e) {
            if (current(s)) Error("Send: " + detail(e, s.key));
            closeSession(s, "Send failed");
          } finally { if (audio) s.pendingAudio.decrementAndGet(); }
        }
      });
      return true;
    } catch (RejectedExecutionException e) {
      if (audio) s.pendingAudio.decrementAndGet();
      if (!audio && current(s)) Error(payload.contains("\"audioStreamEnd\"")
          ? "Microphone audio stream end could not be queued because the outgoing queue is full."
          : "Outgoing queue is full. Please retry the command.");
      return false;
    }
  }

  private void writeNow(Session s, String payload) throws IOException {
    s.writeStarted = System.nanoTime();
    try { s.transport.sendText(payload); }
    finally { s.writeStarted = 0; }
  }

  private void closeSession(final Session s, final String reason) {
    if (s == null || !s.closed.compareAndSet(false, true)) return;
    boolean notify = s.connected;
    s.ready = false;
    s.connected = false;
    s.transport.close(); // Unblock a stalled reader/writer before touching audio.
    s.writer.shutdownNow();
    s.calls.clear();
    synchronized (stateLock) {
      if (s.imageFuture != null) { s.imageFuture.cancel(true); s.imageFuture = null; }
      s.imagePending.set(false);
      final InputStream imageInput = s.imageInput;
      s.imageInput = null;
      if (imageInput != null) threadFactory("GeminiLive-image-cancel").newThread(new Runnable() {
        public void run() { try { imageInput.close(); } catch (IOException ignored) {} }
      }).start();
      imageWorker.purge();
      if (session == s) { session = null; stopMicLocked(); }
      else if (microphone != null && microphone.owner == s) microphone.cancel();
    }
    s.playback.close();
    if (notify) ui.post(new Runnable() {
      public void run() {
        if (!destroyed && s.generation == connectionGeneration) Disconnected(reason == null ? "Socket closed" : reason);
      }
    });
  }

  private void checkTimeouts() {
    Session s = session;
    if (!current(s)) return;
    long now = System.nanoTime();
    String failure = null;
    if (!s.connected && now - s.connectStarted > TimeUnit.SECONDS.toNanos(25)) failure = "Connection timed out";
    else if (s.connected && !s.ready && now - s.setupStarted > TimeUnit.SECONDS.toNanos(20)) failure = "Gemini setup timed out; check model access and API key";
    else if (s.writeStarted != 0 && now - s.writeStarted > TimeUnit.SECONDS.toNanos(10)) failure = "Network write stalled";
    if (failure != null) { Error(failure); closeSession(s, failure); }
  }

  private void stopMicLocked() {
    listenRequested = false;
    ++listenGeneration;
    if (microphone != null) {
      microphone.cancel();
      if (microphoneWorker.remove(microphone)) microphone = null;
    }
  }

  private final class MicRun implements Runnable {
    final Session owner;
    final long generation;
    volatile boolean cancelled;
    volatile boolean started;
    volatile int level;
    volatile AudioRecord recorder;
    private final Object recorderControlLock = new Object();
    AcousticEchoCanceler aec;
    NoiseSuppressor ns;
    boolean startedEvent;
    MicRun(Session owner, long generation) { this.owner = owner; this.generation = generation; }

    void cancel() {
      final AudioRecord record;
      synchronized (this) {
        if (cancelled) return;
        cancelled = true;
        record = recorder;
      }
      if (record != null) threadFactory("GeminiLive-microphone-stop").newThread(new Runnable() {
        public void run() {
          synchronized (recorderControlLock) {
            if (recorder != record) return;
            try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
            catch (RuntimeException ignored) { /* Capture worker retains ownership and releases it. */ }
          }
        }
      }).start();
    }

    private boolean active() { return !cancelled && wantMic(owner, generation); }

    public void run() {
      try {
        if (!active()) return;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); }
        catch (RuntimeException ignored) { /* Priority is an optimization, not a prerequisite. */ }
        openRecorder();
        if (!active()) return;
        started = true;
        ui.post(new Runnable() {
          public void run() {
            if (active() && microphone == MicRun.this) { startedEvent = true; ListeningStarted(); }
          }
        });
        byte[] buffer = new byte[MIC_CHUNK_BYTES];
        int dropped = 0;
        int silentBytes = 0;
        boolean silenceReported = false;
        while (active()) {
          AudioRecord record = recorder;
          int count = record.read(buffer, 0, buffer.length);
          if (!active()) break;
          if (count <= 0) throw new IOException("AudioRecord.read failed (code " + count + "). Check microphone access and competing recording apps.");
          if ((count & 1) != 0) throw new IOException("Microphone returned an incomplete PCM16 sample");
          int peak = 0;
          for (int i = 0; i < count; i += 2) {
            int sample = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
          }
          level = Math.min(100, (peak * 100) / 32768);
          silentBytes = peak == 0 ? Math.min(INPUT_RATE * 2 * 3, silentBytes + count) : 0;
          if (!silenceReported && silentBytes >= INPUT_RATE * 2 * 3) {
            silenceReported = true;
            Error("Microphone has returned only zero samples for 3 seconds. Check Android microphone privacy, APK permission, and other recording apps.");
          }
          if (muteMicWhileSpeaking && owner.playback.speaking) continue;
          String data = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP);
          JSONObject audio = new JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000");
          boolean accepted = send(owner, new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString(), true);
          if (accepted) dropped = 0;
          else if (++dropped == 50) Error("Network cannot keep up with microphone streaming; audio chunks are being dropped to limit latency.");
        }
      } catch (Exception e) {
        if (active()) Error("Microphone: " + detail(e, owner.key));
      } finally {
        releaseRecorder();
        level = 0;
        synchronized (stateLock) {
          if (microphone == this) microphone = null;
          if (generation == listenGeneration) listenRequested = false;
        }
        if (started && ready(owner)) {
          try { send(owner, new JSONObject().put("realtimeInput", new JSONObject().put("audioStreamEnd", true)).toString(), false); }
          catch (Exception e) { if (current(owner)) Error("Audio stream end: " + detail(e, owner.key)); }
        }
        ui.post(new Runnable() { public void run() { if (!destroyed && startedEvent) ListeningStopped(); } });
      }
    }

    private void openRecorder() throws Exception {
      int minimum = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("This device does not support 16 kHz mono PCM16 recording (code " + minimum + ")");
      int nativeBuffer = Math.max(minimum, MIC_CHUNK_BYTES * 4);
      if ((nativeBuffer & 1) != 0) ++nativeBuffer;
      Exception failure = null;
      int[] sources = { MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION };
      for (int source : sources) {
        if (!active()) return;
        try {
          recorder = new AudioRecord(source, INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, nativeBuffer);
          if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord is not initialized");
          if (!active()) return;
          if (echoCancellation && AcousticEchoCanceler.isAvailable()) {
            try { aec = AcousticEchoCanceler.create(recorder.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
            catch (RuntimeException ignored) { /* Optional device effect. */ }
          }
          if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            try { ns = NoiseSuppressor.create(recorder.getAudioSessionId()); if (ns != null) ns.setEnabled(true); }
            catch (RuntimeException ignored) { /* Optional device effect. */ }
          }
          if (!active()) return;
          recorder.startRecording();
          if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Android did not start microphone recording");
          if (!active()) cancel();
          return;
        } catch (SecurityException e) {
          releaseRecorder();
          throw new IOException("Android denied microphone capture. Confirm RECORD_AUDIO is in the rebuilt APK and allowed in Android settings.");
        } catch (Exception e) { failure = e; releaseRecorder(); }
      }
      throw new IOException("Cannot start microphone with MIC or VOICE_COMMUNICATION: " + detail(failure, owner.key));
    }

    private void releaseRecorder() {
      synchronized (recorderControlLock) {
        AudioRecord record = recorder;
        if (record != null) {
          try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); } catch (RuntimeException ignored) { }
        }
        if (aec != null) { try { aec.release(); } catch (RuntimeException ignored) { } aec = null; }
        if (ns != null) { try { ns.release(); } catch (RuntimeException ignored) { } ns = null; }
        if (record != null) { try { record.release(); } catch (RuntimeException ignored) { } }
        recorder = null;
      }
    }
  }

  private static String detail(Throwable e, String key) {
    String message = e == null ? "Unknown error" : e.getMessage();
    if (message == null || message.isEmpty()) message = e == null ? "Unknown error" : e.getClass().getSimpleName();
    if (key != null && !key.isEmpty()) message = message.replace(key, "[redacted]");
    return message.length() > 600 ? message.substring(0, 600) : message;
  }

  private void handleMessage(final Session owner, final String message) {
    if (!current(owner)) return;
    if (rawMessagesEnabled) {
      if (message.length() <= 262144 && owner.pendingRawEvents.incrementAndGet() <= 4) {
        ui.post(new Runnable() {
          public void run() {
            try { if (current(owner) && rawMessagesEnabled) RawMessage(message); }
            finally { owner.pendingRawEvents.decrementAndGet(); }
          }
        });
      } else {
        if (message.length() <= 262144) owner.pendingRawEvents.decrementAndGet();
        if (owner.rawOverflowReported.compareAndSet(false, true)) Error("RawMessage diagnostics exceeded the UI budget and were dropped. Disable RawMessagesEnabled for smooth playback; audio and normal events are unaffected.");
      }
    }
    try {
      JSONObject root = new JSONObject(message);
      JSONObject serverError = root.optJSONObject("error");
      if (serverError != null) {
        Error("Gemini: " + detail(new IOException(serverError.optString("message", "Server error")), owner.key));
        closeSession(owner, "Gemini rejected the session");
        return;
      }
      if (root.has("setupComplete") && !owner.ready) {
        owner.ready = true;
        post(owner, new Runnable() { public void run() { SetupComplete(); } });
      }
      JSONObject tools = root.optJSONObject("toolCall");
      JSONArray calls = tools == null ? null : tools.optJSONArray("functionCalls");
      if (calls != null) for (int i = 0; i < calls.length(); ++i) {
        JSONObject call = calls.getJSONObject(i);
        final String name = call.optString("name", "");
        final String id = call.optString("id", "");
        Object args = call.opt("args");
        final String arguments = args == null || args == JSONObject.NULL ? "{}" : args.toString();
        final PendingCall pending = new PendingCall(name);
        if (!id.isEmpty()) owner.calls.put(id, pending);
        post(owner, new Runnable() {
          public void run() {
            if (!id.isEmpty() && owner.calls.get(id) == pending) FunctionCall(name, arguments, id);
          }
        });
      }
      JSONObject cancellation = root.optJSONObject("toolCallCancellation");
      JSONArray ids = cancellation == null ? null : cancellation.optJSONArray("ids");
      if (ids != null) for (int i = 0; i < ids.length(); ++i) {
        final String id = ids.getString(i);
        owner.calls.remove(id);
        post(owner, new Runnable() { public void run() { FunctionCallCancelled(id); } });
      }
      JSONObject content = root.optJSONObject("serverContent");
      if (content == null) return;
      boolean interrupted = content.optBoolean("interrupted", false);
      if (interrupted) owner.playback.flush();
      JSONObject turn = content.optJSONObject("modelTurn");
      JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
      if (parts != null) for (int i = 0; i < parts.length(); ++i) {
        JSONObject part = parts.getJSONObject(i);
        final String text = part.optString("text", "");
        if (!text.isEmpty()) owner.textEvents.append(2, text);
        JSONObject inline = part.optJSONObject("inlineData");
        if (inline != null && audioEnabled && !interrupted) {
          String mime = inline.optString("mimeType", "");
          if (mime.toLowerCase(Locale.US).startsWith("audio/pcm")) {
            String data = inline.optString("data", "");
            if (!data.isEmpty()) owner.playback.offer(Base64.decode(data, Base64.DEFAULT), pcmRate(mime));
          }
        }
      }
      JSONObject input = content.optJSONObject("inputTranscription");
      if (input != null) {
        final String text = input.optString("text", "");
        if (!text.isEmpty()) owner.textEvents.append(0, text);
      }
      JSONObject output = content.optJSONObject("outputTranscription");
      if (output == null) output = content.optJSONObject("outputAudioTranscription");
      if (output != null) {
        final String text = output.optString("text", "");
        if (!text.isEmpty()) owner.textEvents.append(1, text);
      }
      // turnComplete is generation completion, not evidence that the speaker buffer drained.
      boolean turnComplete = content.optBoolean("turnComplete", false);
      if (turnComplete || content.optBoolean("generationComplete", false)) owner.playback.finishTurn(turnComplete);
      if (turnComplete) post(owner, new Runnable() { public void run() { owner.textEvents.drain(); if (current(owner)) TurnComplete(); } });
    } catch (Exception e) {
      if (current(owner)) Error("Incoming message: " + detail(e, owner.key));
    }
  }

  /** Combine small transcript deltas without allocating an unbounded UI runnable per word. */
  private final class TextEvents {
    final Session owner;
    final StringBuilder[] pending = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
    boolean scheduled;
    boolean overflowReported;
    final Runnable delivery = new Runnable() { public void run() { drain(); } };
    TextEvents(Session owner) { this.owner = owner; }
    void append(int kind, String text) {
      boolean overflow = false;
      synchronized (this) {
        if (!current(owner)) return;
        if (text.length() > 65536 - pending[kind].length()) {
          if (!overflowReported) { overflowReported = true; overflow = true; }
        } else {
          pending[kind].append(text);
          if (!scheduled) { scheduled = true; ui.postDelayed(delivery, 100); }
        }
      }
      if (overflow) Error("Transcript UI buffer exceeded its budget; some diagnostic text was omitted. Reduce work in transcript event handlers.");
    }
    void drain() {
      String[] text = new String[3];
      synchronized (this) {
        ui.removeCallbacks(delivery);
        scheduled = false;
        for (int i = 0; i < 3; i++) { text[i] = pending[i].toString(); pending[i].setLength(0); }
      }
      if (!current(owner)) return;
      if (current(owner) && !text[0].isEmpty()) InputTranscription(text[0]);
      if (current(owner) && !text[1].isEmpty()) OutputTranscription(text[1]);
      if (current(owner) && !text[2].isEmpty()) TextReceived(text[2]);
    }
  }

  private static int pcmRate(String mime) throws IOException {
    int rate = 24000;
    for (String field : mime.split(";")) {
      String item = field.trim();
      if (item.toLowerCase(Locale.US).startsWith("rate=")) {
        try { rate = Integer.parseInt(item.substring(5).trim()); }
        catch (NumberFormatException e) { throw new IOException("Invalid PCM sample rate"); }
      }
    }
    if (rate < 8000 || rate > 96000) throw new IOException("Unsupported PCM sample rate: " + rate);
    return rate;
  }

  private static final class PcmChunk {
    final byte[] data;
    final int rate;
    final long epoch;
    final long receivedAt = System.nanoTime();
    PcmChunk(byte[] data, int rate, long epoch) { this.data = data; this.rate = rate; this.epoch = epoch; }
  }

  private final class Playback implements Runnable {
    private static final long MAX_QUEUED_MICROS = 30000000L;
    private static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    private static final long STARTUP_MICROS = 120000L;
    private final Session owner;
    private final Object queueLock = new Object();
    private final ArrayDeque<PcmChunk> queue = new ArrayDeque<PcmChunk>();
    private volatile boolean closed;
    private volatile boolean flushing;
    private volatile long epoch;
    volatile boolean speaking;
    private AudioTrack track; // Every native track operation belongs to the playback worker.
    private int trackRate;
    private int queuedBytes;
    private long queuedMicros;
    private int activeBytes;
    private long activeMicros;
    private long writtenFrames;
    private boolean trackPlaying;
    private float appliedVolume = -1;
    private boolean generationFinished;
    private boolean discardUntilTurnComplete;
    private Thread worker;
    Playback(Session owner) { this.owner = owner; }

    void start() {
      worker = threadFactory("GeminiLive-playback").newThread(this);
      worker.start();
    }

    private long duration(byte[] pcm, int rate) { return (pcm.length / 2L) * 1000000L / rate; }

    void offer(byte[] pcm, int rate) throws IOException {
      if (closed || !current(owner) || !audioEnabled || pcm.length == 0) return;
      if ((pcm.length & 1) != 0) throw new IOException("Assistant audio contains an incomplete PCM16 sample");
      boolean overflow = false;
      synchronized (queueLock) {
        if (closed || !audioEnabled || discardUntilTurnComplete) return;
        long micros = duration(pcm, rate);
        if (pcm.length > MAX_QUEUED_BYTES - queuedBytes - activeBytes
            || micros > MAX_QUEUED_MICROS - queuedMicros - activeMicros) {
          resetLocked();
          discardUntilTurnComplete = true;
          overflow = true;
        } else {
          queue.addLast(new PcmChunk(pcm, rate, epoch));
          queuedBytes += pcm.length;
          queuedMicros += micros;
          generationFinished = false;
          speaking = true;
          queueLock.notifyAll();
        }
      }
      if (overflow) Error("Audio response exceeded the bounded playback capacity. This response was stopped; use shorter replies or check device playback. Later turns can play normally.");
    }

    void finishTurn(boolean turnComplete) {
      synchronized (queueLock) {
        generationFinished = true;
        if (turnComplete) discardUntilTurnComplete = false;
        queueLock.notifyAll();
      }
    }

    void updateVolume() { synchronized (queueLock) { queueLock.notifyAll(); } }

    int bufferedMilliseconds() {
      synchronized (queueLock) { return (int)((queuedMicros + activeMicros) / 1000); }
    }

    private void resetLocked() {
      ++epoch;
      flushing = true;
      queue.clear();
      queuedBytes = 0;
      queuedMicros = 0;
      activeBytes = 0;
      activeMicros = 0;
      generationFinished = false;
      speaking = false;
      queueLock.notifyAll();
    }

    // Public controls change state only; pause/flush/release must never run on the UI thread.
    void flush() {
      synchronized (queueLock) {
        resetLocked();
        discardUntilTurnComplete = false;
      }
    }

    void close() {
      synchronized (queueLock) { closed = true; resetLocked(); }
      Thread thread = worker;
      if (thread != null) thread.interrupt();
    }

    public void run() {
      long appliedEpoch = 0;
      try {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); }
        catch (RuntimeException ignored) { }
        while (!closed) {
          if (appliedEpoch != epoch) {
            long resetting = epoch;
            releaseTrack();
            synchronized (queueLock) {
              appliedEpoch = resetting;
              if (epoch == resetting) {
                flushing = false;
                speaking = !closed && audioEnabled && !queue.isEmpty();
              }
            }
          }
          applyVolume();
          PcmChunk chunk = null;
          synchronized (queueLock) {
            if (closed) break;
            if (appliedEpoch != epoch) continue;
            PcmChunk first = queue.peekFirst();
            boolean priming = track == null && first != null && !generationFinished
                && queuedMicros < STARTUP_MICROS
                && System.nanoTime() - first.receivedAt < TimeUnit.MICROSECONDS.toNanos(STARTUP_MICROS);
            if (first == null || priming) queueLock.wait(priming ? 10 : 20);
            else {
              chunk = queue.removeFirst();
              queuedBytes -= chunk.data.length;
              queuedMicros -= duration(chunk.data, chunk.rate);
              activeBytes = chunk.data.length;
              activeMicros = duration(chunk.data, chunk.rate);
            }
          }
          if (chunk == null) { refreshSpeaking(); continue; }
          try {
            int offset = 0;
            while (offset < chunk.data.length && !closed && audioEnabled && chunk.epoch == epoch) {
              ensureTrack(chunk.rate);
              applyVolume();
              int count = Math.min(chunk.data.length - offset, (chunk.rate / 50) * 2);
              int written;
              try {
                written = android.os.Build.VERSION.SDK_INT >= 23
                    ? track.write(chunk.data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                    : track.write(chunk.data, offset, count);
              } catch (RuntimeException e) {
                if (closed || !audioEnabled || chunk.epoch != epoch) break;
                throw e;
              }
              if (closed || !audioEnabled || chunk.epoch != epoch) break;
              if (written < 0 || written > count || (written & 1) != 0) throw new IOException("AudioTrack.write failed (code " + written + ")");
              if (written == 0) {
                synchronized (queueLock) { if (!closed && chunk.epoch == epoch) queueLock.wait(5); }
                continue; // A full nonblocking hardware buffer is normal backpressure, not a failed turn.
              }
              offset += written;
              writtenFrames += written / 2;
              synchronized (queueLock) {
                if (chunk.epoch == epoch) {
                  activeBytes = chunk.data.length - offset;
                  activeMicros = (activeBytes / 2L) * 1000000L / chunk.rate;
                }
              }
              maybeStartTrack();
            }
          } catch (Exception e) {
            if (!closed && current(owner) && chunk.epoch == epoch) {
              Error("Audio playback: " + detail(e, owner.key));
              flush();
            }
          } finally {
            synchronized (queueLock) { if (chunk.epoch == epoch) { activeBytes = 0; activeMicros = 0; } }
          }
          maybeStartTrack();
          refreshSpeaking();
        }
      } catch (InterruptedException ignored) {
        if (!closed) { Thread.currentThread().interrupt(); Error("Audio playback worker interrupted."); }
      } catch (Exception e) {
        if (!closed && current(owner)) Error("Audio playback: " + detail(e, owner.key));
      } finally {
        closed = true;
        synchronized (queueLock) { resetLocked(); }
        releaseTrack();
      }
    }

    private void ensureTrack(int rate) throws IOException {
      if (track != null && trackRate == rate) return;
      releaseTrack();
      int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("Unsupported speaker sample rate: " + rate);
      AudioTrack created = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, (rate / 5) * 2), AudioTrack.MODE_STREAM);
      if (created.getState() != AudioTrack.STATE_INITIALIZED) { created.release(); throw new IOException("AudioTrack initialization failed"); }
      track = created;
      trackRate = rate;
      writtenFrames = 0;
      trackPlaying = false;
      appliedVolume = -1;
      applyVolume();
    }

    private void applyVolume() {
      float requested = volume;
      if (track != null && appliedVolume != requested) {
        appliedVolume = requested;
        try { track.setStereoVolume(requested, requested); }
        catch (RuntimeException e) { if (current(owner)) Error("Volume: " + detail(e, owner.key)); }
      }
    }

    private void maybeStartTrack() {
      if (track == null || trackPlaying || flushing || closed || writtenFrames == 0) return;
      boolean readyToPlay;
      synchronized (queueLock) {
        readyToPlay = writtenFrames >= trackRate / 10 || (activeBytes == 0 && queue.isEmpty());
      }
      if (readyToPlay) { track.play(); trackPlaying = true; }
    }

    private void refreshSpeaking() {
      long played = track == null ? writtenFrames : ((long) track.getPlaybackHeadPosition() & 0xffffffffL);
      synchronized (queueLock) {
        speaking = !closed && audioEnabled && (!queue.isEmpty() || activeBytes > 0 || (!flushing && played < writtenFrames));
      }
    }

    private void releaseTrack() {
      AudioTrack existing = track;
      track = null;
      writtenFrames = 0;
      trackPlaying = false;
      if (existing != null) {
        try { existing.pause(); } catch (RuntimeException ignored) { }
        try { existing.flush(); } catch (RuntimeException ignored) { }
        try { existing.release(); } catch (RuntimeException ignored) { }
      }
    }
  }


  private void fetchCatalog(final String suppliedKey, final boolean models) {
    if (destroyed) return;
    if (suppliedKey == null || suppliedKey.trim().isEmpty()) { Error("API key is empty."); return; }
    final String key = suppliedKey.trim();
    final int slot = models ? 0 : 1;
    final long generation;
    synchronized (stateLock) { generation = ++catalogGeneration[slot]; }
    try {
      catalogWorker.execute(new Runnable() {
        public void run() {
          try {
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            String token = "";
            for (int page = 0; page < 20; ++page) {
              if (!catalogCurrent(slot, generation)) return;
              String path = models ? "/v1beta/models?pageSize=1000" : "/v1beta/voices?type=prebuilt&page_size=1000";
              if (!token.isEmpty()) path += (models ? "&pageToken=" : "&page_token=") + URLEncoder.encode(token, "UTF-8");
              JSONObject root = getCatalogPage(path, key);
              JSONArray entries = root.optJSONArray(models ? "models" : "voices");
              if (entries != null) for (int i = 0; i < entries.length(); ++i) {
                JSONObject entry = entries.getJSONObject(i);
                if (models) {
                  String name = entry.optString("name", "");
                  if (name.startsWith("models/")) name = name.substring(7);
                  JSONArray methods = entry.optJSONArray("supportedGenerationMethods");
                  boolean live = false;
                  if (methods != null) {
                    for (int method = 0; method < methods.length(); ++method)
                      if ("bidiGenerateContent".equalsIgnoreCase(methods.optString(method))) live = true;
                  } else {
                    String lower = name.toLowerCase(Locale.US);
                    live = lower.contains("live") || lower.contains("native-audio");
                  }
                  if (live && !name.isEmpty()) names.add(name);
                } else {
                  if (!"prebuilt".equalsIgnoreCase(entry.optString("type", "prebuilt"))) continue;
                  String id = entry.optString("id", "");
                  if (id.isEmpty()) {
                    id = entry.optString("name", "");
                    if (id.startsWith("voices/")) id = id.substring(7);
                  }
                  if (!id.isEmpty()) names.add(id);
                }
              }
              token = root.optString(models ? "nextPageToken" : "next_page_token", "");
              if (token.isEmpty()) token = root.optString(models ? "next_page_token" : "nextPageToken", "");
              if (token.isEmpty()) break;
              if (page == 19) throw new IOException("Catalog exceeds the pagination safety limit");
            }
            final YailList list = YailList.makeList(new ArrayList<String>(names));
            ui.post(new Runnable() {
              public void run() {
                if (catalogCurrent(slot, generation)) { if (models) ModelsLoaded(list); else VoicesLoaded(list); }
              }
            });
          } catch (Exception e) {
            if (catalogCurrent(slot, generation)) Error((models ? "RefreshModels: " : "RefreshVoices: ") + detail(e, key));
          }
        }
      });
    } catch (RejectedExecutionException e) { if (!destroyed) Error("Catalog request queue is full. Please retry."); }
  }

  private boolean catalogCurrent(int slot, long generation) {
    synchronized (stateLock) { return !destroyed && catalogGeneration[slot] == generation; }
  }

  private static JSONObject getCatalogPage(String path, String key) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com" + path).openConnection();
    try {
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(15000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("x-goog-api-key", key);
      int status = connection.getResponseCode();
      InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
      if (stream == null) throw new IOException("Catalog HTTP " + status);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          if (bytes.size() + count > 2 * 1024 * 1024) throw new IOException("Catalog response exceeds size limit");
          bytes.write(buffer, 0, count);
        }
      } finally { stream.close(); }
      String body = new String(bytes.toByteArray(), UTF8);
      if (status < 200 || status >= 300) {
        String reason = "";
        try { JSONObject error = new JSONObject(body).optJSONObject("error"); if (error != null) reason = error.optString("message", ""); }
        catch (Exception ignored) { }
        throw new IOException("HTTP " + status + (reason.isEmpty() ? "" : ": " + reason));
      }
      return new JSONObject(body);
    } finally { connection.disconnect(); }
  }

  /** Single-use, RFC 6455 transport. Close remains safe while connect or a write is blocked. */
  private static final class Transport {
    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String ENDPOINT = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final java.nio.charset.Charset UTF8 = java.nio.charset.Charset.forName("UTF-8");
    private static final java.nio.charset.Charset ASCII = java.nio.charset.Charset.forName("US-ASCII");
    private final Object stateLock = new Object();
    private final Object writeLock = new Object();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final byte[] writeMask = new byte[4];
    private final byte[] writeHeader = new byte[14];
    private final byte[] writeBuffer = new byte[8192];
    private volatile java.net.Socket activeSocket;
    private volatile java.io.InputStream input;
    private volatile java.io.OutputStream output;
    private volatile boolean closed;
    private volatile String closeReason = "";
    private boolean connectStarted;

    void connect(String apiKey) throws java.io.IOException {
      if (apiKey == null || apiKey.trim().length() == 0) {
        throw new java.io.IOException("API key is empty.");
      }
      synchronized (stateLock) {
        if (closed) throw new java.io.IOException("Connection was cancelled.");
        if (connectStarted) throw new java.io.IOException("Transport already used.");
        connectStarted = true;
      }
      java.net.Socket tcp = new java.net.Socket();
      try {
        publishSocket(tcp);
        tcp.setTcpNoDelay(true);
        tcp.connect(new java.net.InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS);
        tcp.setSoTimeout(CONNECT_TIMEOUT_MS);
        javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket)
            ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                .createSocket(tcp, HOST, 443, true);
        publishSocket(tls);
        tls.setTcpNoDelay(true);
        tls.setSoTimeout(CONNECT_TIMEOUT_MS);
        java.util.ArrayList<String> protocols = new java.util.ArrayList<String>();
        for (String protocol : tls.getSupportedProtocols()) {
          if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) protocols.add(protocol);
        }
        if (protocols.isEmpty()) throw new javax.net.ssl.SSLHandshakeException("TLS 1.2 or newer is required.");
        // Android 4.x can support TLS 1.2 without enabling it by default.
        tls.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
        tls.startHandshake();
        if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, tls.getSession())) {
          throw new javax.net.ssl.SSLHandshakeException("TLS certificate hostname validation failed.");
        }
        java.io.InputStream incoming = new java.io.BufferedInputStream(tls.getInputStream(), 8192);
        java.io.OutputStream outgoing = new java.io.BufferedOutputStream(tls.getOutputStream(), 8192);
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
        String request = "GET " + ENDPOINT + "?key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8") + " HTTP/1.1\r\n"
            + "Host: " + HOST + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        outgoing.write(request.getBytes(ASCII));
        outgoing.flush();
        verifyUpgrade(readHttpHeaders(incoming), key);
        // Live sessions may legitimately remain idle. Closing the socket cancels a blocking read.
        tls.setSoTimeout(0);
        synchronized (stateLock) {
          if (closed) throw new java.io.IOException("Connection was cancelled.");
          input = incoming;
          output = outgoing;
        }
      } catch (java.io.IOException failure) {
        setCloseReason("Connection setup failed.");
        close();
        throw failure;
      } catch (RuntimeException failure) {
        setCloseReason("Connection setup failed.");
        close();
        throw new java.io.IOException("WebSocket connection setup failed.", failure);
      }
    }

    private void publishSocket(java.net.Socket socket) throws java.io.IOException {
      synchronized (stateLock) {
        if (!closed) {
          activeSocket = socket;
          return;
        }
      }
      try { socket.close(); } catch (java.io.IOException ignored) { }
      throw new java.io.IOException("Connection was cancelled.");
    }

    private static String readHttpHeaders(java.io.InputStream stream) throws java.io.IOException {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(1024);
      int matched = 0;
      byte[] terminator = new byte[] {13, 10, 13, 10};
      while (bytes.size() < MAX_HEADER_BYTES) {
        int value = stream.read();
        if (value < 0) throw new java.io.EOFException("Connection ended during the WebSocket handshake.");
        bytes.write(value);
        if (value == terminator[matched]) matched++;
        else matched = value == 13 ? 1 : 0;
        if (matched == 4) return new String(bytes.toByteArray(), ASCII);
      }
      throw new java.io.IOException("WebSocket handshake headers exceed the size limit.");
    }

    private static void verifyUpgrade(String headers, String key) throws java.io.IOException {
      String[] lines = headers.split("\r\n");
      if (lines.length == 0) throw new java.io.IOException("Invalid WebSocket handshake response.");
      String[] status = lines[0].split(" +", 3);
      if (status.length < 2 || !"HTTP/1.1".equals(status[0]) || !status[1].matches("[0-9]{3}")) {
        throw new java.io.IOException("Invalid WebSocket handshake status.");
      }
      if (!"101".equals(status[1])) {
        throw new java.io.IOException("WebSocket handshake rejected (HTTP " + status[1] + ").");
      }
      java.util.HashMap<String, String> fields = new java.util.HashMap<String, String>();
      for (int index = 1; index < lines.length; index++) {
        String line = lines[index];
        int colon = line.indexOf(':');
        if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
          throw new java.io.IOException("Invalid WebSocket handshake header.");
        }
        String name = line.substring(0, colon).toLowerCase(java.util.Locale.US);
        if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) {
          throw new java.io.IOException("Invalid WebSocket handshake header name.");
        }
        String value = line.substring(colon + 1).trim();
        for (int character = colon + 1; character < line.length(); character++) {
          char valueCharacter = line.charAt(character);
          if ((valueCharacter < 32 && valueCharacter != '\t') || valueCharacter == 127) {
            throw new java.io.IOException("Invalid WebSocket handshake header value.");
          }
        }
        String previous = fields.get(name);
        if (previous != null && "sec-websocket-accept".equals(name)) {
          throw new java.io.IOException("Duplicate WebSocket accept header.");
        }
        fields.put(name, previous == null ? value : previous + "," + value);
      }
      if (!containsToken(fields.get("upgrade"), "websocket")
          || !containsToken(fields.get("connection"), "upgrade")) {
        throw new java.io.IOException("WebSocket upgrade headers are missing or invalid.");
      }
      // This client negotiates no extensions or subprotocols.
      if (fields.containsKey("sec-websocket-extensions") || fields.containsKey("sec-websocket-protocol")) {
        throw new java.io.IOException("WebSocket server selected an unrequested extension or subprotocol.");
      }
      String expected;
      try {
        expected = android.util.Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(ASCII)),
            android.util.Base64.NO_WRAP);
      } catch (java.security.NoSuchAlgorithmException failure) {
        throw new java.io.IOException("Required WebSocket handshake digest is unavailable.", failure);
      }
      String accepted = fields.get("sec-websocket-accept");
      if (accepted == null || !java.security.MessageDigest.isEqual(expected.getBytes(ASCII), accepted.getBytes(ASCII))) {
        throw new java.io.IOException("WebSocket handshake accept validation failed.");
      }
    }

    private static boolean containsToken(String value, String expected) {
      if (value == null) return false;
      for (String token : value.split(",")) {
        if (expected.equalsIgnoreCase(token.trim())) return true;
      }
      return false;
    }

    String readMessage() throws java.io.IOException {
      try {
        return readMessageInternal();
      } catch (java.io.IOException failure) {
        setCloseReason(failure instanceof java.io.EOFException
            ? "Connection ended during a WebSocket frame (code 1006)."
            : "WebSocket read failed (code 1006).");
        close();
        throw failure;
      }
    }

    private String readMessageInternal() throws java.io.IOException {
      java.io.InputStream stream = input;
      if (closed) return null;
      if (stream == null) throw new java.io.IOException("WebSocket is not connected.");
      java.io.ByteArrayOutputStream fragments = null;
      int messageOpcode = -1;
      for (;;) {
        if (closed) return null;
        int first = stream.read();
        if (first < 0) {
          setCloseReason("Connection ended without a WebSocket close frame (code 1006).");
          close();
          return null;
        }
        int second = readByte(stream);
        boolean fin = (first & 128) != 0;
        int opcode = first & 15;
        if ((first & 112) != 0) throw protocolFailure(1002, "Unexpected WebSocket RSV bits.");
        if ((second & 128) != 0) throw protocolFailure(1002, "Server sent a masked WebSocket frame.");
        if (opcode != 0 && opcode != 1 && opcode != 2 && opcode != 8 && opcode != 9 && opcode != 10) {
          throw protocolFailure(1002, "Unsupported WebSocket opcode.");
        }
        boolean control = opcode >= 8;
        long length = second & 127;
        if (control && (!fin || length > 125)) throw protocolFailure(1002, "Invalid WebSocket control frame.");
        if (length == 126) {
          length = ((long) readByte(stream) << 8) | readByte(stream);
          if (length < 126) throw protocolFailure(1002, "Nonminimal WebSocket frame length.");
        } else if (length == 127) {
          int high = readByte(stream);
          if ((high & 128) != 0) throw protocolFailure(1002, "Invalid WebSocket frame length.");
          length = high;
          for (int index = 1; index < 8; index++) length = (length << 8) | readByte(stream);
          if (length < 65536) throw protocolFailure(1002, "Nonminimal WebSocket frame length.");
        }
        if (length > MAX_MESSAGE_BYTES) throw protocolFailure(1009, "WebSocket message exceeds the size limit.");
        if (!control) {
          if (opcode == 0 && messageOpcode == -1) throw protocolFailure(1002, "Unexpected WebSocket continuation.");
          if (opcode != 0 && messageOpcode != -1) throw protocolFailure(1002, "New message during WebSocket fragmentation.");
          if (fragments != null && length > MAX_MESSAGE_BYTES - fragments.size()) {
            throw protocolFailure(1009, "WebSocket message exceeds the size limit.");
          }
        }
        byte[] payload = new byte[(int) length];
        readFully(stream, payload);
        if (opcode == 8) {
          if (payload.length == 1) throw protocolFailure(1002, "Invalid WebSocket close payload.");
          if (payload.length == 0) {
            setCloseReason("WebSocket closed without a status code.");
          } else {
            int code = ((payload[0] & 255) << 8) | (payload[1] & 255);
            if (!validCloseCode(code)) throw protocolFailure(1002, "Invalid WebSocket close status code.");
            String reason;
            try { reason = decodeUtf8(payload, 2, payload.length - 2); }
            catch (java.nio.charset.CharacterCodingException failure) {
              throw protocolFailure(1007, "Invalid UTF-8 in WebSocket close reason.");
            }
            setCloseReason("WebSocket closed (code " + code + ")" + (reason.length() == 0 ? "." : ": " + reason));
          }
          try { sendFrame(8, payload); } catch (java.io.IOException ignored) { /* Peer may already have closed. */ }
          close();
          return null;
        }
        if (opcode == 9) { sendFrame(10, payload); continue; }
        if (opcode == 10) continue;
        if (opcode != 0) messageOpcode = opcode;
        if (fin && fragments == null) {
          try { return decodeUtf8(payload, 0, payload.length); }
          catch (java.nio.charset.CharacterCodingException failure) {
            throw protocolFailure(1007, "Invalid UTF-8 in WebSocket message.");
          }
        }
        if (fragments == null) fragments = new java.io.ByteArrayOutputStream(Math.max(256, payload.length));
        fragments.write(payload, 0, payload.length);
        if (fin) {
          byte[] message = fragments.toByteArray();
          try { return decodeUtf8(message, 0, message.length); }
          catch (java.nio.charset.CharacterCodingException failure) {
            throw protocolFailure(1007, "Invalid UTF-8 in WebSocket message.");
          }
        }
      }
    }

    private static boolean validCloseCode(int code) {
      return (code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006)
          || (code >= 3000 && code <= 4999);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length)
        throws java.nio.charset.CharacterCodingException {
      return UTF8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes, offset, length)).toString();
    }

    private static int readByte(java.io.InputStream stream) throws java.io.IOException {
      int value = stream.read();
      if (value < 0) throw new java.io.EOFException("Connection ended during a WebSocket frame (code 1006).");
      return value;
    }

    private static void readFully(java.io.InputStream stream, byte[] bytes) throws java.io.IOException {
      int offset = 0;
      while (offset < bytes.length) {
        int count = stream.read(bytes, offset, bytes.length - offset);
        if (count < 0) throw new java.io.EOFException("Connection ended during a WebSocket frame (code 1006).");
        if (count == 0) { bytes[offset++] = (byte) readByte(stream); }
        else offset += count;
      }
    }

    void sendText(String message) throws java.io.IOException {
      if (message == null) throw new java.io.IOException("WebSocket message is null.");
      byte[] payload = message.getBytes(UTF8);
      if (payload.length > MAX_MESSAGE_BYTES) throw new java.io.IOException("Outgoing WebSocket message exceeds the size limit.");
      try {
        sendFrame(1, payload);
      } catch (java.io.IOException failure) {
        setCloseReason("WebSocket write failed (code 1006).");
        close();
        throw failure;
      }
    }

    private void sendFrame(int opcode, byte[] payload) throws java.io.IOException {
      synchronized (writeLock) {
        java.io.OutputStream stream = output;
        if (closed || stream == null) throw new java.io.IOException("WebSocket is closed or not connected.");
        int position = 0;
        writeHeader[position++] = (byte) (128 | opcode);
        if (payload.length < 126) writeHeader[position++] = (byte) (128 | payload.length);
        else if (payload.length <= 65535) {
          writeHeader[position++] = (byte) (128 | 126);
          writeHeader[position++] = (byte) (payload.length >>> 8);
          writeHeader[position++] = (byte) payload.length;
        } else {
          writeHeader[position++] = (byte) (128 | 127);
          for (int shift = 56; shift >= 0; shift -= 8) writeHeader[position++] = (byte) ((long) payload.length >>> shift);
        }
        random.nextBytes(writeMask);
        for (int index = 0; index < writeMask.length; index++) writeHeader[position++] = writeMask[index];
        stream.write(writeHeader, 0, position);
        for (int offset = 0; offset < payload.length;) {
          int count = Math.min(writeBuffer.length, payload.length - offset);
          for (int index = 0; index < count; index++) {
            writeBuffer[index] = (byte) (payload[offset + index] ^ writeMask[(offset + index) & 3]);
          }
          stream.write(writeBuffer, 0, count);
          offset += count;
        }
        stream.flush();
      }
    }

    private java.io.IOException protocolFailure(int code, String reason) {
      setCloseReason("WebSocket protocol error (code " + code + "): " + reason);
      byte[] payload = new byte[] {(byte) (code >>> 8), (byte) code};
      try { sendFrame(8, payload); } catch (java.io.IOException ignored) { /* Closing is still required. */ }
      close();
      return new java.io.IOException(closeReason);
    }

    String getCloseReason() { return closeReason; }

    private void setCloseReason(String reason) {
      synchronized (stateLock) {
        if (closeReason.length() == 0) closeReason = reason;
      }
    }

    void close() {
      java.net.Socket socket;
      synchronized (stateLock) {
        if (closed) return;
        closed = true;
        if (closeReason.length() == 0) closeReason = "Connection closed.";
        socket = activeSocket;
        activeSocket = null;
      }
      // Never wait on writeLock: closing must interrupt a writer blocked by network backpressure.
      if (socket != null) {
        try { socket.close(); } catch (java.io.IOException ignored) { /* Socket is already unusable. */ }
      }
    }
  }
}
