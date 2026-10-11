import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import com.google.appinventor.components.runtime.util.MediaUtil;
import com.fabiyon.geminilive.GeminiLive;
import com.google.appinventor.components.runtime.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Executes the compiled production class against deterministic Android/Form doubles.
 * These tests prove control flow and resource ownership; hardware/APK tests remain required.
 */
public final class RuntimeTests {
  private static int passed, failed;
  private interface Test { void run() throws Exception; }
  private interface Condition { boolean met() throws Exception; }
  private static final Class<?> SESSION;
  static { try { SESSION = Class.forName("com.fabiyon.geminilive.GeminiLive$Session"); }
    catch (Exception e) { throw new ExceptionInInitializerError(e); } }

  private static final class Fixture implements AutoCloseable {
    final Form form = new Form();
    final GeminiLive extension;
    final Object session;
    final Capture output = new Capture();
    Fixture(boolean permission, boolean ready) throws Exception {
      AudioRecord.reset(); AudioTrack.reset(); EventDispatcher.reset();
      Bitmap.reset(); BitmapFactory.reset(); MediaUtil.reset();
      ExifInterface.orientation = ExifInterface.ORIENTATION_NORMAL; ExifInterface.fails = false;
      AcousticEchoCanceler.created.set(0); AcousticEchoCanceler.released.set(0);
      NoiseSuppressor.created.set(0); NoiseSuppressor.released.set(0);
      AcousticEchoCanceler.fail = false; NoiseSuppressor.fail = false;
      form.permissionGranted = permission;
      extension = new GeminiLive(new ComponentContainer() {
        public Form $form() { return form; }
        public android.app.Activity $context() { return form; }
      });
      Constructor<?> constructor = SESSION.getDeclaredConstructor(GeminiLive.class, String.class);
      constructor.setAccessible(true);
      session = constructor.newInstance(extension, "test-secret-never-used-for-network");
      set(session, "ready", ready); set(session, "connected", true);
      Object transport = get(session, "transport");
      set(transport, "output", output);
      set(extension, "session", session);
    }
    void start() throws Exception { Handler.runSync(new Runnable() { public void run() { extension.StartListening(); } }); Handler.awaitIdle(); }
    void stop() throws Exception { Handler.runSync(new Runnable() { public void run() { extension.StopListening(); } }); }
    void awaitStarted() throws Exception { await(new Condition() { public boolean met() { return extension.IsListening() && EventDispatcher.count("ListeningStarted") == 1 && output.snapshot().length > 6; } }); }
    void awaitStopped() throws Exception {
      await(new Condition() { public boolean met() { return AudioRecord.active.get() == 0 && AudioRecord.created.get() == AudioRecord.released.get() && getQuiet(extension, "microphone") == null; } });
      Handler.awaitIdle();
      if (!(Boolean)get(extension, "destroyed")) await(new Condition() {
        public boolean met() { return EventDispatcher.count("ListeningStarted") == EventDispatcher.count("ListeningStopped"); }
      });
    }
    void awaitWrites() throws Exception {
      final ThreadPoolExecutor writer = (ThreadPoolExecutor) get(session, "writer");
      await(new Condition() { public boolean met() { return writer.isShutdown() || (writer.getQueue().isEmpty() && writer.getActiveCount() == 0); } });
    }
    void awaitImage() throws Exception {
      final ThreadPoolExecutor worker = (ThreadPoolExecutor)get(extension, "imageWorker");
      await(new Condition() { public boolean met() { return !extension.IsSendingImage() && worker.getQueue().isEmpty() && worker.getActiveCount() == 0; } });
      awaitWrites(); Handler.awaitIdle();
    }
    void inbound(String payload) throws Exception { invoke(extension, "handleMessage", new Class<?>[] {SESSION, String.class}, session, payload); }
    void startPlayback() throws Exception { invoke(get(session, "playback"), "start", new Class<?>[0]); }
    void offerPcm(int bytes) throws Exception { invoke(get(session, "playback"), "offer", new Class<?>[] {byte[].class, int.class}, new byte[bytes], 24000); }
    public void close() throws Exception {
      extension.onDestroy(); awaitStopped();
      final ThreadPoolExecutor worker = (ThreadPoolExecutor)get(extension, "imageWorker");
      await(new Condition() { public boolean met() { return worker.isTerminated(); } });
      check(Bitmap.created.get() == Bitmap.recycled.get(), "Every image bitmap must be recycled");
      check(MediaUtil.opened.get() == MediaUtil.closed.get(), "Every image stream must be closed");
      if (form.imageCacheDir != null) {
        equal(0, form.imageCacheDir.list().length);
        check(form.imageCacheDir.delete(), "Image test cache cleanup failed");
      }
      check(AudioRecord.created.get() == AudioRecord.released.get(), "Every recorder must be released");
      check(AcousticEchoCanceler.created.get() == AcousticEchoCanceler.released.get(), "Every echo canceller must be released");
      check(NoiseSuppressor.created.get() == NoiseSuppressor.released.get(), "Every noise suppressor must be released");
      check(AudioTrack.created.get() == AudioTrack.released.get(), "Every audio track must be released");
    }
  }

  private static final class Capture extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    public synchronized void write(int value) { bytes.write(value); }
    public synchronized void write(byte[] value, int offset, int length) { bytes.write(value, offset, length); }
    synchronized byte[] snapshot() { return bytes.toByteArray(); }
  }
  private static final class GateOutput extends OutputStream {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public void write(int value) throws IOException {
      entered.countDown();
      try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test gate timed out"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    public void write(byte[] value, int offset, int length) throws IOException { write(0); }
  }

  public static void main(String[] args) throws Exception {
    test("not ready cannot request permission or create a recorder", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, false)) {
        f.start(); Handler.awaitIdle();
        equal(0, f.form.requests); equal(0, AudioRecord.created.get()); equal(1, EventDispatcher.count("Error"));
      }
    }});
    test("APK permission grant continues pending capture without another block call", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, true)) {
        f.start(); equal(1, f.form.requests); equal(0, AudioRecord.created.get());
        f.form.respond(true); f.awaitStarted();
        await(new Condition() { public boolean met() { return AudioRecord.created.get() > 0; } });
        f.stop(); f.awaitStopped(); f.awaitWrites();
        equal(1, EventDispatcher.count("ListeningStarted")); equal(1, EventDispatcher.count("ListeningStopped"));
        assertUiEvents(); assertStream(f.output.snapshot(), true);
      }
    }});
    test("already granted Companion permission starts directly", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.start(); f.awaitStarted(); equal(0, f.form.requests); f.stop(); f.awaitStopped(); }
    }});
    test("permission denial dispatches Form event and allows retry", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, true)) {
        f.start(); f.form.respond(false); Handler.awaitIdle();
        equal(1, f.form.deniedEvents); equal(1, EventDispatcher.count("Error")); equal(0, AudioRecord.created.get());
        f.form.permissionGranted = true; f.start(); f.awaitStarted(); f.stop(); f.awaitStopped();
      }
    }});
    test("StopListening cancels a late permission grant", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, true)) { f.start(); f.stop(); f.form.respond(true); Handler.awaitIdle(); equal(0, AudioRecord.created.get()); check(!f.extension.IsListening(), "Late grant restarted stopped capture"); }
    }});
    test("Disconnect cancels a late permission grant", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, true)) { f.start(); f.extension.Disconnect(); f.form.respond(true); Handler.awaitIdle(); equal(0, AudioRecord.created.get()); check(!f.extension.IsReady(), "Disconnected session remained ready"); }
    }});
    test("duplicate starts during the permission prompt are coalesced", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(false, true)) { f.start(); f.start(); equal(1, f.form.requests); f.form.respond(true); f.awaitStarted(); equal(1, AudioRecord.created.get()); f.stop(); f.awaitStopped(); }
    }});
    test("MIC is the default recording source", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.start(); f.awaitStarted(); equal(MediaRecorder.AudioSource.MIC, AudioRecord.sources.get(0)); f.stop(); f.awaitStopped(); }
    }});
    test("unsupported MIC falls back and releases the rejected recorder", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { AudioRecord.rejectSource = MediaRecorder.AudioSource.MIC; f.start(); f.awaitStarted(); equal(2, AudioRecord.created.get()); equal(1, AudioRecord.released.get()); equal(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioRecord.sources.get(1)); f.stop(); f.awaitStopped(); }
    }});
    test("both unsupported sources report failure and release every recorder", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        AudioRecord.failAllSources = true; f.start();
        await(new Condition() { public boolean met() { return EventDispatcher.count("Error") == 1; } }); f.awaitStopped();
        equal(2, AudioRecord.created.get()); equal(2, AudioRecord.released.get()); check(!f.extension.IsListening(), "Failed recorder marked listening");
      }
    }});
    test("unsupported 16 kHz format fails before constructing AudioRecord", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { AudioRecord.minimumBuffer = AudioRecord.ERROR_BAD_VALUE; f.start(); await(new Condition() { public boolean met() { return EventDispatcher.count("Error") == 1; } }); f.awaitStopped(); equal(0, AudioRecord.created.get()); }
    }});
    test("Android SecurityException reports APK permission diagnosis and releases recorder", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { AudioRecord.denyStart = true; f.start(); await(new Condition() { public boolean met() { return EventDispatcher.count("Error") == 1; } }); f.awaitStopped(); equal(1, AudioRecord.created.get()); check(errorContains("RECORD_AUDIO"), "APK permission diagnosis is missing"); }
    }});
    test("optional effect failure does not prevent capture", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { AcousticEchoCanceler.fail = true; NoiseSuppressor.fail = true; f.start(); f.awaitStarted(); f.stop(); f.awaitStopped(); equal(0, EventDispatcher.count("Error")); }
    }});
    test("negative read failures stop cleanly with one useful error", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.start(); f.awaitStarted(); AudioRecord.readFailure = AudioRecord.ERROR_DEAD_OBJECT; await(new Condition() { public boolean met() { return EventDispatcher.count("Error") == 1; } }); f.awaitStopped(); equal(1, EventDispatcher.count("ListeningStopped")); }
    }});
    test("twenty immediate stop-start cycles never overlap recorders", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        for (int i = 0; i < 20; i++) {
          f.start(); await(new Condition() { public boolean met() { return AudioRecord.active.get() == 1; } }); f.stop();
        }
        f.awaitStopped(); f.awaitWrites(); equal(1, AudioRecord.maxActive.get()); assertStream(f.output.snapshot(), false);
      }
    }});
    test("background screen lifecycle disconnects and releases capture", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { equal(1, f.form.stops.size()); equal(1, f.form.destroys.size()); f.start(); f.awaitStarted(); f.form.stops.get(0).onStop(); f.awaitStopped(); check(!f.extension.IsConnected(), "onStop left connected session"); }
    }});
    test("destroy and delete are idempotent and suppress late permission grants", new Test() { public void run() throws Exception {
      Fixture f = new Fixture(false, true); f.start(); f.extension.onDestroy(); f.extension.onDelete(); f.form.respond(true); Handler.awaitIdle(); equal(0, AudioRecord.created.get()); f.close();
    }});
    test("text and microphone audio share one masked ordered writer", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.start(); f.awaitStarted(); f.extension.SendText("one"); f.extension.SendText("two"); f.stop(); f.awaitStopped(); f.awaitWrites();
        List<JSONObject> messages = messages(f.output.snapshot()); List<String> texts = new ArrayList<String>();
        for (JSONObject message : messages) if (message.has("clientContent")) texts.add(message.getJSONObject("clientContent").getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));
        check(texts.equals(Arrays.asList("one", "two")), "User texts lost order or interleaved frame bytes"); assertStream(f.output.snapshot(), true);
      }
    }});
    test("slow network bounds unsent microphone audio to eight chunks", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        GateOutput output = new GateOutput(); set(get(f.session, "transport"), "output", output);
        int accepted = 0;
        try {
          for (int i = 0; i < 200; i++) {
            boolean queued = (Boolean)invoke(f.extension, "send", new Class<?>[] {SESSION, String.class, boolean.class}, f.session, "{\"chunk\":" + i + "}", true);
            if (queued) accepted++;
          }
          check(output.entered.await(1, TimeUnit.SECONDS), "Writer never entered gate");
          equal(8, accepted); equal(8, ((AtomicInteger)get(f.session, "pendingAudio")).get());
          check(((ThreadPoolExecutor)get(f.session, "writer")).getQueue().size() <= 7, "Audio send queue grew beyond latency budget");
        } finally { output.release.countDown(); }
        f.awaitWrites(); equal(0, ((AtomicInteger)get(f.session, "pendingAudio")).get());
      }
    }});
    test("full control queue reports rejection instead of allocating unbounded tasks", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        GateOutput output = new GateOutput(); set(get(f.session, "transport"), "output", output);
        try {
          f.extension.SendText("first"); check(output.entered.await(1, TimeUnit.SECONDS), "Writer never entered gate");
          for (int i = 0; i < 80; i++) f.extension.SendText("queued " + i);
          equal(32, ((ThreadPoolExecutor)get(f.session, "writer")).getQueue().size()); Handler.awaitIdle();
          check(EventDispatcher.count("Error") >= 1, "Queue overflow not reported");
        } finally { output.release.countDown(); }
        f.awaitWrites();
      }
    }});
    test("stalled write watchdog disconnects once", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        set(f.session, "writeStarted", System.nanoTime() - TimeUnit.SECONDS.toNanos(11));
        invoke(f.extension, "checkTimeouts", new Class<?>[0]); Handler.awaitIdle();
        check(!f.extension.IsConnected(), "Watchdog left stalled connection open"); equal(1, EventDispatcher.count("Error")); equal(1, EventDispatcher.count("Disconnected"));
        invoke(f.extension, "checkTimeouts", new Class<?>[0]); Handler.awaitIdle(); equal(1, EventDispatcher.count("Disconnected"));
      }
    }});
    test("setupComplete dispatches once and unlocks microphone readiness", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, false)) {
        f.inbound("{\"setupComplete\":{}}"); f.inbound("{\"setupComplete\":{}}"); Handler.awaitIdle();
        check(f.extension.IsReady(), "Setup acknowledgement did not unlock readiness"); equal(1, EventDispatcher.count("SetupComplete"));
      }
    }});
    test("disabled RawMessage preserves transcription and text events", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.extension.RawMessagesEnabled(false);
        f.inbound("{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"text\":\"answer\"}]},\"inputTranscription\":{\"text\":\"question\"},\"outputTranscription\":{\"text\":\"answer\"},\"turnComplete\":true}}");
        Handler.awaitIdle(); equal(0, EventDispatcher.count("RawMessage")); equal(1, EventDispatcher.count("TextReceived")); equal(1, EventDispatcher.count("InputTranscription")); equal(1, EventDispatcher.count("OutputTranscription")); equal(1, EventDispatcher.count("TurnComplete")); assertUiEvents();
      }
    }});
    test("function cancellation rejects late replies", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.inbound("{\"toolCall\":{\"functionCalls\":[{\"id\":\"call-1\",\"name\":\"weather\",\"args\":{\"city\":\"Delhi\"}}]}}"); Handler.awaitIdle();
        f.inbound("{\"toolCallCancellation\":{\"ids\":[\"call-1\"]}}"); Handler.awaitIdle();
        equal(1, EventDispatcher.count("FunctionCall")); equal(1, EventDispatcher.count("FunctionCallCancelled"));
        f.extension.SendFunctionResult("call-1", "weather", "{}"); Handler.awaitIdle(); equal(1, EventDispatcher.count("Error")); equal(0, f.output.snapshot().length);
      }
    }});
    test("answered function call cannot be replied to twice", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.inbound("{\"toolCall\":{\"functionCalls\":[{\"id\":\"call-2\",\"name\":\"weather\",\"args\":{}}]}}");
        f.extension.SendFunctionResult("call-2", "weather", "{\"temperature\":25}"); f.awaitWrites();
        f.extension.SendFunctionResult("call-2", "weather", "{}"); Handler.awaitIdle();
        equal(1, EventDispatcher.count("Error")); equal(1, messages(f.output.snapshot()).size());
      }
    }});
    test("stale sessions cannot dispatch incoming events", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.extension.Disconnect(); Handler.awaitIdle(); EventDispatcher.reset(); f.inbound("{\"serverContent\":{\"inputTranscription\":{\"text\":\"stale\"}}}"); Handler.awaitIdle(); equal(0, EventDispatcher.events.size()); }
    }});
    test("function declarations replace names and survive atomic setup snapshots", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.extension.AddFunction("weather", "old", "{\"type\":\"object\"}"); f.extension.AddFunction("weather", "new", "{\"type\":\"object\"}");
        JSONObject setup = new JSONObject((String)invoke(f.extension, "makeSetup", new Class<?>[0]));
        JSONArray declarations = setup.getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        equal(1, declarations.length()); check(declarations.getJSONObject(0).getString("description").equals("new"), "Function replacement lost new declaration");
        f.extension.ClearFunctions(); setup = new JSONObject((String)invoke(f.extension, "makeSetup", new Class<?>[0])); check(!setup.getJSONObject("setup").has("tools"), "Cleared tools still sent");
      }
    }});
    test("cancelled function callbacks are suppressed before their UI delivery", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        new Handler().post(new Runnable() { public void run() { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }});
        check(entered.await(1, TimeUnit.SECONDS), "UI gate did not start");
        try {
          f.inbound("{\"toolCall\":{\"functionCalls\":[{\"id\":\"queued-call\",\"name\":\"weather\",\"args\":{}}]}}");
          f.inbound("{\"toolCallCancellation\":{\"ids\":[\"queued-call\"]}}");
        } finally { release.countDown(); }
        Handler.awaitIdle(); equal(0, EventDispatcher.count("FunctionCall")); equal(1, EventDispatcher.count("FunctionCallCancelled"));
      }
    }});
    test("cancelled function result is removed before its queued write", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        GateOutput output = new GateOutput(); set(get(f.session, "transport"), "output", output);
        try {
          f.extension.SendText("writer gate"); check(output.entered.await(1, TimeUnit.SECONDS), "Writer gate did not start");
          f.inbound("{\"toolCall\":{\"functionCalls\":[{\"id\":\"queued-result\",\"name\":\"weather\",\"args\":{}}]}}");
          f.extension.SendFunctionResult("queued-result", "weather", "{}");
          f.inbound("{\"toolCallCancellation\":{\"ids\":[\"queued-result\"]}}");
          set(get(f.session, "transport"), "output", f.output);
        } finally { output.release.countDown(); }
        f.awaitWrites(); equal(0, f.output.snapshot().length);
      }
    }});
    test("empty function IDs cannot produce untracked results", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) { f.extension.SendFunctionResult("", "weather", "{}"); Handler.awaitIdle(); equal(1, EventDispatcher.count("Error")); equal(0, f.output.snapshot().length); }
    }});
    test("playback turn completion waits for the speaker buffer to drain", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        AudioTrack.autoAdvanceHead = false; f.startPlayback(); f.offerPcm(960);
        await(new Condition() { public boolean met() { return AudioTrack.totalWrittenFrames.get() == 480; } });
        f.inbound("{\"serverContent\":{\"turnComplete\":true}}"); Handler.awaitIdle();
        check((Boolean)get(get(f.session, "playback"), "speaking"), "TurnComplete cleared speaking before hardware playback drained");
        AudioTrack.advanceAllHeads(); await(new Condition() { public boolean met() { return !(Boolean)getQuiet(getQuiet(f.session, "playback"), "speaking"); } });
      }
    }});
    test("StopAudio unblocks a pending AudioTrack write and accepts future playback", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        AudioTrack.blockWrites = true; f.startPlayback(); f.offerPcm(960);
        check(AudioTrack.writeEntered.await(1, TimeUnit.SECONDS), "Playback did not block in AudioTrack.write");
        f.extension.StopAudio(); equal(1, AudioTrack.released.get()); Handler.awaitIdle(); equal(0, EventDispatcher.count("Error"));
        AudioTrack.blockWrites = false; f.offerPcm(640);
        await(new Condition() { public boolean met() { return AudioTrack.totalWrittenFrames.get() == 320; } }); equal(2, AudioTrack.created.get());
      }
    }});
    test("new PCM arriving during flush waits for reclamation and plays afterward", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        AudioTrack.blockWrites = true; f.startPlayback(); f.offerPcm(960);
        check(AudioTrack.writeEntered.await(1, TimeUnit.SECONDS), "Playback did not block");
        AudioTrack.pauseEntered = new CountDownLatch(1); AudioTrack.pauseRelease = new CountDownLatch(1);
        Thread flush = new Thread(new Runnable() { public void run() { f.extension.StopAudio(); } }, "test-flush"); flush.start();
        check(AudioTrack.pauseEntered.await(1, TimeUnit.SECONDS), "Flush did not reach pause");
        try {
          AudioTrack.blockWrites = false; f.offerPcm(640);
          equal(1, AudioTrack.created.get()); equal(640, (Integer)get(get(f.session, "playback"), "queuedBytes"));
        } finally { AudioTrack.pauseRelease.countDown(); }
        flush.join(2000); check(!flush.isAlive(), "Flush never completed");
        await(new Condition() { public boolean met() { return AudioTrack.totalWrittenFrames.get() == 320; } }); equal(2, AudioTrack.created.get());
        Handler.awaitIdle(); equal(0, EventDispatcher.count("Error"));
      }
    }});
    test("playback read error resets its track and recovers for future chunks", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        AudioTrack.writeFailure = -6; f.startPlayback(); f.offerPcm(960);
        await(new Condition() { public boolean met() { return EventDispatcher.count("Error") == 1 && AudioTrack.released.get() == 1; } });
        AudioTrack.writeFailure = 0; f.offerPcm(640);
        await(new Condition() { public boolean met() { return AudioTrack.totalWrittenFrames.get() == 320; } }); equal(2, AudioTrack.created.get());
      }
    }});
    imageTests();
    System.out.println("Runtime tests: " + passed + " passed, " + failed + " failed");
    if (failed != 0) throw new AssertionError("Runtime validation failed");
  }

  private static void imageTests() throws Exception {
    test("image requires setup before opening media", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, false)) { f.extension.SendImage("content://photo/1", "Describe"); Handler.awaitIdle(); equal(0, MediaUtil.opened.get()); equal(1, EventDispatcher.count("Error")); }
    }});
    test("image validation rejects absent paths and prompts without reserving worker", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.extension.SendImage("", "Describe"); f.extension.SendImage("photo.jpg", " ");
        f.extension.SendImage("photo.jpg", new String(new char[16385]).replace('\0', 'x'));
        Handler.awaitIdle(); equal(3, EventDispatcher.count("Error")); equal(0, MediaUtil.opened.get()); check(!f.extension.IsSendingImage(), "Invalid image reserved worker");
      }
    }});
    test("photo and prompt use consecutive native realtime frames off UI", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        Handler.runSync(new Runnable() { public void run() { f.extension.SendImage("content://photo/1", "What is here?"); } }); f.awaitImage();
        List<JSONObject> frames = messages(f.output.snapshot()); equal(2, frames.size());
        JSONObject video = frames.get(0).getJSONObject("realtimeInput").getJSONObject("video");
        check(video.getString("mimeType").equals("image/jpeg"), "Wrong image MIME");
        equal(1024, java.util.Base64.getDecoder().decode(video.getString("data")).length);
        check(frames.get(1).getJSONObject("realtimeInput").getString("text").equals("What is here?"), "Prompt lost or sent before frame");
        equal(2, BitmapFactory.sampleSize); equal(1024, Bitmap.compressedWidth); equal(768, Bitmap.compressedHeight);
        equal(1, EventDispatcher.count("ImageSent")); check(!f.extension.IsSendingImage(), "Reservation not released");
        check(Bitmap.compressionThreads.get(0).equals("GeminiLive-image"), "Image encoded on UI thread"); assertUiEvents();
      }
    }});
    test("remote and data image URIs are rejected without opening network", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        for (String path : Arrays.asList("https://example.com/image.jpg", "data:image/jpeg;base64,AA==", "ftp://host/image")) {
          f.extension.SendImage(path, "Describe"); f.awaitImage();
        }
        equal(0, MediaUtil.opened.get()); equal(3, EventDispatcher.count("Error")); equal(0, f.output.snapshot().length);
      }
    }});
    test("empty, unreadable and corrupt images fail with cleanup and allow retry", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        MediaUtil.data = new byte[0]; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        MediaUtil.data = new byte[128]; MediaUtil.readFails = true; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        MediaUtil.readFails = false; BitmapFactory.badBounds = true; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        BitmapFactory.badBounds = false; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        equal(3, EventDispatcher.count("Error")); equal(1, EventDispatcher.count("ImageSent"));
      }
    }});
    test("oversize source is rejected before bitmap allocation", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        MediaUtil.data = new byte[20 * 1024 * 1024 + 1]; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        check(errorContains("20 MiB"), "Missing source size diagnostic"); equal(0, Bitmap.created.get()); equal(0, f.output.snapshot().length);
      }
    }});
    test("EXIF rotation preserves camera portrait orientation", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        ExifInterface.orientation = ExifInterface.ORIENTATION_ROTATE_90; f.extension.SendImage("camera.jpg", "Describe"); f.awaitImage();
        equal(768, Bitmap.compressedWidth); equal(1024, Bitmap.compressedHeight);
        equal(Bitmap.created.get(), Bitmap.recycled.get());
        for (int orientation = 2; orientation <= 8; orientation++) {
          Object matrix = invoke(f.extension, "imageOrientationMatrix", new Class<?>[] {int.class}, orientation);
          check(matrix != null, "Missing EXIF orientation " + orientation);
          android.graphics.Matrix transform = (android.graphics.Matrix)matrix;
          if (orientation == 2 || orientation == 5 || orientation == 7) check(transform.scaleX == -1, "Missing EXIF reflection");
          if (orientation == 4) check(transform.scaleY == -1, "Missing vertical reflection");
        }
      }
    }});
    test("JPEG budget retries lower quality and rejects persistent oversized output", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        Bitmap.jpegBytesByQuality = new int[] {600000, 300000, 100000}; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        check(Bitmap.qualities.equals(Arrays.asList(80, 60)), "Missing lower-quality retry"); equal(1, EventDispatcher.count("ImageSent"));
        Bitmap.jpegBytesByQuality = new int[] {600000, 600000, 600000}; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        check(errorContains("512 KiB"), "Missing JPEG budget diagnostic"); equal(1, EventDispatcher.count("ImageSent"));
      }
    }});
    test("image OOM and encoder failure release resources and reservation", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        BitmapFactory.outOfMemory = true; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        check(errorContains("Not enough memory"), "OOM not surfaced"); BitmapFactory.outOfMemory = false;
        Bitmap.compressionFails = true; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        Bitmap.compressionFails = false; f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage(); equal(1, EventDispatcher.count("ImageSent"));
      }
    }});
    test("image reservation persists during blocked network writes", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        GateOutput gate = new GateOutput(); set(get(f.session, "transport"), "output", gate);
        try {
          f.extension.SendImage("photo.jpg", "Describe"); check(gate.entered.await(1, TimeUnit.SECONDS), "Image writer did not block");
          check(f.extension.IsSendingImage(), "Image reservation released before network write");
          f.extension.SendImage("second.jpg", "Describe"); Handler.awaitIdle(); equal(1, MediaUtil.opened.get());
          check(errorContains("already being"), "Duplicate pending photo not rejected"); equal(0, EventDispatcher.count("ImageSent"));
        } finally { gate.release.countDown(); }
        f.awaitImage(); equal(1, EventDispatcher.count("ImageSent"));
      }
    }});
    test("disconnect during image read cancels stream and prevents stale sends", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        MediaUtil.blockRead = true; f.extension.SendImage("content://photo/1", "Describe");
        check(MediaUtil.readEntered.await(1, TimeUnit.SECONDS), "Image read did not reach gate");
        f.extension.Disconnect(); f.awaitImage(); equal(0, f.output.snapshot().length); equal(0, EventDispatcher.count("ImageSent"));
        equal(0, EventDispatcher.count("Error")); equal(MediaUtil.opened.get(), MediaUtil.closed.get());
      }
    }});
    test("full writer queue rejects photo and releases pending state", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        GateOutput gate = new GateOutput(); set(get(f.session, "transport"), "output", gate);
        try {
          f.extension.SendText("gate"); check(gate.entered.await(1, TimeUnit.SECONDS), "Writer gate not entered");
          for (int i = 0; i < 32; i++) f.extension.SendText("queued");
          f.extension.SendImage("photo.jpg", "Describe");
          await(new Condition() { public boolean met() { return errorContains("retry the image") && !f.extension.IsSendingImage(); } });
          equal(0, EventDispatcher.count("ImageSent"));
        } finally { gate.release.countDown(); }
        f.awaitImage();
      }
    }});
    test("replaced session cannot send old photo into the new connection", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        MediaUtil.blockRead = true; f.extension.SendImage("old-photo.jpg", "Old prompt");
        check(MediaUtil.readEntered.await(1, TimeUnit.SECONDS), "Old image did not reach read gate");
        Constructor<?> constructor = SESSION.getDeclaredConstructor(GeminiLive.class, String.class); constructor.setAccessible(true);
        Object replacement = constructor.newInstance(f.extension, "new-test-key"); Capture replacementOutput = new Capture();
        set(replacement, "ready", true); set(replacement, "connected", true); set(replacement, "generation", 1L);
        set(get(replacement, "transport"), "output", replacementOutput);
        set(f.extension, "connectionGeneration", 1L); set(f.extension, "session", replacement);
        invoke(f.extension, "closeSession", new Class<?>[] {SESSION, String.class}, f.session, "Replaced");
        final ThreadPoolExecutor imageWorker = (ThreadPoolExecutor)get(f.extension, "imageWorker");
        await(new Condition() { public boolean met() { return imageWorker.getActiveCount() == 0; } });
        MediaUtil.blockRead = false; f.extension.SendImage("new-photo.jpg", "New prompt"); f.awaitImage();
        equal(0, f.output.snapshot().length); List<JSONObject> frames = messages(replacementOutput.snapshot()); equal(2, frames.size());
        check(frames.get(1).getJSONObject("realtimeInput").getString("text").equals("New prompt"), "Old prompt reached replacement");
        equal(1, EventDispatcher.count("ImageSent")); equal(0, EventDispatcher.count("Error"));
      }
    }});
    test("failed image network write closes session and releases reservation", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        set(get(f.session, "transport"), "output", new OutputStream() { public void write(int value) throws IOException { throw new IOException("Simulated image send failure"); } });
        f.extension.SendImage("photo.jpg", "Describe"); f.awaitImage();
        await(new Condition() { public boolean met() { return !f.extension.IsConnected() && errorContains("Simulated image send failure"); } });
        equal(0, EventDispatcher.count("ImageSent")); check(!f.extension.IsSendingImage(), "Failed writer retained photo reservation");
      }
    }});
    test("opening external camera stops live session and photo waits for reconnect", new Test() { public void run() throws Exception {
      try (Fixture f = new Fixture(true, true)) {
        f.form.stops.get(0).onStop(); f.extension.SendImage("camera.jpg", "Describe"); Handler.awaitIdle();
        equal(0, MediaUtil.opened.get()); check(!f.extension.IsReady(), "Camera activity kept stale connection ready");
      }
    }});
  }

  private static void test(String name, Test test) {
    try { test.run(); passed++; System.out.println("PASS " + name); }
    catch (Throwable error) { failed++; System.err.println("FAIL " + name); error.printStackTrace(); }
  }
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  private static void equal(int expected, int actual) { check(expected == actual, "Expected " + expected + ", got " + actual); }
  private static Object get(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
  private static Object getQuiet(Object owner, String name) { try { return get(owner, name); } catch (Exception e) { throw new RuntimeException(e); } }
  private static void set(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
  private static Object invoke(Object owner, String name, Class<?>[] types, Object... values) throws Exception { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); try { return method.invoke(owner, values); } catch (InvocationTargetException e) { if (e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw e; } }
  private static void await(Condition condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.met()) { if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for asynchronous result"); Thread.sleep(2); }
  }
  private static boolean errorContains(String text) { synchronized (EventDispatcher.events) { for (EventDispatcher.Event event : EventDispatcher.events) if (event.name.equals("Error") && event.arguments[0].toString().contains(text)) return true; } return false; }
  private static void assertUiEvents() { synchronized (EventDispatcher.events) { for (EventDispatcher.Event event : EventDispatcher.events) check(event.thread.equals("fake-main"), event.name + " dispatched outside UI thread"); } }
  private static List<JSONObject> messages(byte[] frames) throws Exception {
    ByteArrayInputStream input = new ByteArrayInputStream(frames); List<JSONObject> result = new ArrayList<JSONObject>();
    while (input.available() > 0) {
      int first = input.read(), second = input.read(); check(first == 129, "Expected final text frame"); check((second & 128) != 0, "Client frame not masked");
      long length = second & 127;
      if (length == 126) length = (input.read() << 8) | input.read();
      else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | input.read(); }
      check(length <= 4 * 1024 * 1024, "Invalid length"); byte[] mask = new byte[4]; equal(4, input.read(mask));
      byte[] payload = new byte[(int)length]; equal(payload.length, input.read(payload));
      for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      result.add(new JSONObject(new String(payload, "UTF-8")));
    }
    return result;
  }
  private static void assertStream(byte[] frames, boolean requireAudio) throws Exception {
    boolean audio = false, end = false;
    for (JSONObject message : messages(frames)) {
      JSONObject realtime = message.optJSONObject("realtimeInput"); if (realtime == null) continue;
      if (realtime.has("audio")) {
        if (requireAudio) check(!end, "Captured audio was emitted after audioStreamEnd without a new stream"); audio = true;
        JSONObject pcm = realtime.getJSONObject("audio"); equal(640, java.util.Base64.getDecoder().decode(pcm.getString("data")).length);
        check(pcm.getString("mimeType").equals("audio/pcm;rate=16000"), "Incorrect microphone sample rate");
      }
      if (realtime.optBoolean("audioStreamEnd")) end = true;
    }
    if (requireAudio) check(audio, "No microphone audio frames sent");
    check(end, "Audio stream never ended");
  }
}
