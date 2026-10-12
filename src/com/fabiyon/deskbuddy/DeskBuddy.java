package com.fabiyon.deskbuddy;

import android.content.*;
import android.graphics.*;
import android.graphics.Canvas;
import android.hardware.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.view.*;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailList;
import java.util.*;

@DesignerComponent(version=1, description="Independent cyan-eye desk buddy: layered emotions, reactions, autonomous idle animations, touch, motion, battery and Wi-Fi. Draws natively without an AI/network dependency.", category=ComponentCategory.EXTENSION, nonVisible=true, iconName="")
@SimpleObject(external=true)
@UsesPermissions(permissionNames="android.permission.ACCESS_NETWORK_STATE")
public class DeskBuddy extends AndroidNonvisibleComponent implements OnResumeListener, OnStopListener, OnDestroyListener, Deleteable, SensorEventListener {
  public static final String[] EMOTIONS = names("idle happy excited sad angry bored curious confused shy love surprised scared proud guilty jealous disgusted frustrated calm sleepy dizzy mischievous focused embarrassed grateful crying sleeping");
  public static final String[] REACTIONS = names("wave nod shake_head laugh giggle clap dance jump celebrate facepalm wink blow_kiss hug think peek stretch yawn sneeze hiccup fall get_up spin shrug point look_around listen_closely cheer sleep");
  public static final String[] SYSTEMS = names("charging fully_charged low_battery critical_battery battery_draining plugged_in unplugged wifi_connected wifi_disconnected connecting listening processing speaking ai_error reconnecting microphone_muted camera_active notification update shutdown startup");
  public static final String[] CONVERSATIONS = names("understanding agreeing disagreeing searching remembering forgetful eureka uncertain apologizing encouraging comforting impressed suspicious sarcastic playful storytelling explaining waiting interrupted goodbye");
  public static final String[] IDLES = names("random_blink eye_tracking head_tilt look_left_right look_left look_right breathing foot_tapping whistling humming daydreaming playing reading gaming drinking_coffee eating sleeping waking_up exercising dancing_alone chasing_butterfly catching_falling_object screen_edge_peek attention_seeking getting_impatient reacting_to_touch reacting_to_drag");
  static String[] names(String value) { return value.split(" "); }
  public static String normalize(String value) {
    String n = value == null ? "" : value.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    if (n.equals("focus")) return "focused";
    if (n.equals("shake")) return "shake_head";
    if (n.equals("wifi_off")) return "wifi_disconnected";
    if (n.equals("mic_on")) return "listening";
    if (n.equals("mic_muted")) return "microphone_muted";
    if (n.equals("look_left_/_right")) return "look_left_right";
    return n;
  }
  static boolean contains(String[] values, String name) { for (String value : values) if (value.equals(name)) return true; return false; }

  /** Pure state machine; no Android, AI, timers, or sensor resources are owned here. */
  public static final class Brain {
    public String emotion = "idle", reaction = "", conversation = "", previousAwake = "idle";
    public long reactionStart, reactionEnd, now, lastInteraction, nextIdle, nextBlink, blinkStart = -1000, pausedAt = -1;
    public boolean autonomous = true, naturalBlink = true, touching;
    public int sleepAfterSeconds = 120, batteryLevel = -1;
    public boolean plugged;
    public double speechLevel = -1;
    public float gazeX, gazeY;
    public final LinkedHashSet<String> manual = new LinkedHashSet<String>(), device = new LinkedHashSet<String>();
    public final ArrayDeque<String[]> events = new ArrayDeque<String[]>();
    final Random random;
    public Brain(long time) { random = new Random(time ^ 471L); now = lastInteraction = time; nextIdle = time + 8000; nextBlink = time + 2200; }
    void event(String kind, String value) { if (events.size() == 64) events.removeFirst(); events.addLast(new String[] {kind, value}); }
    public void setEmotion(String value) {
      String name = normalize(value);
      if (!contains(EMOTIONS, name)) throw new IllegalArgumentException("Unknown emotion: " + value);
      if (name.equals(emotion)) return;
      if (name.equals("sleeping")) previousAwake = emotion;
      emotion = name; event("emotion", name);
    }
    public void react(String value, int milliseconds) {
      String name = normalize(value);
      if (!contains(REACTIONS, name) && !contains(SYSTEMS, name) && !contains(CONVERSATIONS, name) && !contains(IDLES, name) && !contains(EMOTIONS, name)) throw new IllegalArgumentException("Unknown reaction: " + value);
      if (!reaction.isEmpty()) event("finished", reaction);
      reaction = name; reactionStart = now; reactionEnd = now + Math.max(250, Math.min(10000, milliseconds)); event("reaction", name);
    }
    public void stopReaction() { if (!reaction.isEmpty()) event("finished", reaction); reaction = ""; reactionEnd = now; }
    public void wake() { if (emotion.equals("sleeping")) { setEmotion(previousAwake.equals("sleeping") ? "idle" : previousAwake); react("waking_up", 2000); } lastInteraction = now; nextIdle = now + 8000; }
    public boolean has(String name) { return manual.contains(name) || device.contains(name) || conversation.equals(name); }
    public void overlay(String value, boolean active, boolean automatic) {
      String name = normalize(value);
      if (!contains(SYSTEMS, name)) throw new IllegalArgumentException("Unknown system overlay: " + value);
      boolean before = has(name); Set<String> target = automatic ? device : manual;
      if (active) target.add(name); else target.remove(name);
      if (before != has(name)) event("overlay", name + ":" + has(name));
    }
    public void converse(String value) {
      String name = normalize(value);
      if (!name.isEmpty() && !contains(CONVERSATIONS, name) && !contains(SYSTEMS, name)) throw new IllegalArgumentException("Unknown conversation state: " + value);
      conversation = name;
      if (!name.isEmpty()) { wake(); if (contains(CONVERSATIONS, name)) react(name, 2500); }
    }
    public void pause(long time) { if (pausedAt < 0) pausedAt = time; }
    public void resume(long time) {
      if (pausedAt >= 0) { long delta = Math.max(0, time - pausedAt); reactionStart += delta; reactionEnd += delta; nextIdle += delta; nextBlink += delta; blinkStart += delta; lastInteraction += delta; pausedAt = -1; }
      now = time;
    }
    public void advance(long time) {
      if (pausedAt >= 0) return;
      now = time;
      if (!reaction.isEmpty() && time >= reactionEnd) stopReaction();
      if (naturalBlink && time >= nextBlink) { blinkStart = time; nextBlink = time + 2500 + random.nextInt(3500); }
      boolean busy = touching || has("speaking") || has("listening") || has("processing") || has("connecting") || has("update");
      if (!autonomous || busy) return;
      boolean resting = emotion.equals("idle") || emotion.equals("calm") || emotion.equals("bored") || emotion.equals("sleepy");
      if (resting && sleepAfterSeconds > 0 && time - lastInteraction >= sleepAfterSeconds * 1000L) { setEmotion("sleeping"); return; }
      if (resting && reaction.isEmpty() && time >= nextIdle) {
        String name = IDLES[random.nextInt(IDLES.length - 3)];
        if (name.equals("sleeping")) name = "yawn"; // Long sleep is controlled by inactivity or Sleep().
        react(name, 2500 + random.nextInt(1800)); event("idle", name); nextIdle = time + 8000 + random.nextInt(8000);
      }
    }
    public double progress() { return reaction.isEmpty() ? 0 : Math.max(0, Math.min(1, (now - reactionStart) / (double)(reactionEnd - reactionStart))); }
    public String visual() { return reaction.isEmpty() ? emotion : reaction; }
    public void power(int level, boolean connected) {
      int old = batteryLevel; boolean previous = plugged;
      batteryLevel = Math.max(0, Math.min(100, level)); plugged = connected;
      overlay("charging", connected && batteryLevel < 100, true);
      overlay("fully_charged", connected && batteryLevel == 100, true);
      overlay("critical_battery", !connected && batteryLevel <= 5, true);
      overlay("low_battery", !connected && batteryLevel > 5 && batteryLevel <= 20, true);
      overlay("battery_draining", !connected && old >= 0 && batteryLevel < old, true);
      if (previous != connected) event("charging", Boolean.toString(connected));
      if (old != batteryLevel) event("battery", Integer.toString(batteryLevel));
    }
  }

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Brain brain = new Brain(SystemClock.uptimeMillis());
  private final SensorManager sensors;
  private final Sensor accelerometer;
  private AvatarView avatar;
  private boolean foreground = true, enabled = true, disposed, sensorRegistered, receiverRegistered;
  private boolean sensorsEnabled = true, deviceMonitoring = true, autoReactions = true;
  private boolean keepScreenOn;
  private boolean wifiKnown, wifiConnected;
  private int fps = 30;
  private double shakeThreshold = 9.5;
  private final MotionTracker motion = new MotionTracker();
  private final Runnable frame = new Runnable() { public void run() {
    if (!running()) return;
    if (avatar.isShown() && avatar.getWidth() > 0 && avatar.getHeight() > 0) { brain.advance(SystemClock.uptimeMillis()); emit(); if (running()) avatar.invalidate(); }
    if (running()) scheduleFrame(avatar.isShown() ? Math.max(16, 1000 / fps) : 500);
  }};
  private final BroadcastReceiver receiver = new BroadcastReceiver() { public void onReceive(Context context, Intent intent) {
    if (!running() || !deviceMonitoring) return;
    if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
      int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      if (level >= 0 && scale > 0) {
        boolean before = brain.plugged; int previous = brain.batteryLevel;
        brain.now = SystemClock.uptimeMillis(); brain.power((int)(level * 100L / scale), intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        if (autoReactions && previous >= 0 && before != brain.plugged) brain.react(brain.plugged ? "plugged_in" : "unplugged", 1600);
        else if (autoReactions && previous >= 0 && previous < 100 && brain.batteryLevel == 100 && brain.plugged) brain.react("fully_charged", 2500);
        else if (autoReactions && !brain.plugged && previous > 5 && brain.batteryLevel <= 5) brain.react("critical_battery", 2200);
        else if (autoReactions && !brain.plugged && previous > 20 && brain.batteryLevel <= 20) brain.react("low_battery", 2200);
        emit();
      }
    }
    updateWifi();
  }};

  public DeskBuddy(ComponentContainer container) {
    super(container.$form());
    sensors = (SensorManager)form.getSystemService(Context.SENSOR_SERVICE);
    accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    form.registerForOnResume(this); form.registerForOnStop(this); form.registerForOnDestroy(this);
  }
  private boolean running() { return !disposed && enabled && foreground && avatar != null; }
  private void action(final Runnable operation) { if (disposed) return; if (Looper.myLooper() == Looper.getMainLooper()) operation.run(); else ui.post(new Runnable() { public void run() { if (!disposed) operation.run(); } }); }
  private void change(final Runnable operation) { action(new Runnable() { public void run() { try { brain.now = brain.pausedAt >= 0 ? brain.pausedAt : SystemClock.uptimeMillis(); operation.run(); emit(); if (avatar != null) avatar.invalidate(); } catch (Exception e) { Error(e.getMessage()); } } }); }

  @SimpleFunction(description="Attach to an empty arrangement with a nonzero size. This extension adds only its own native view and does not remove your other components.")
  public void Attach(final AndroidViewComponent arrangement) { action(new Runnable() { public void run() {
    if (arrangement == null || !(arrangement.getView() instanceof ViewGroup)) { Error("Attach requires an arrangement such as VerticalArrangement."); return; }
    detachView(); avatar = new AvatarView(form); ((ViewGroup)arrangement.getView()).addView(avatar, new ViewGroup.LayoutParams(-1, -1));
    brain.resume(SystemClock.uptimeMillis()); brain.react("startup", 2000); refresh(); emit();
  }}); }
  private void detachView() { ui.removeCallbacks(frame); if (avatar != null) { avatar.cancelTouch(); if (avatar.getParent() instanceof ViewGroup) ((ViewGroup)avatar.getParent()).removeView(avatar); avatar = null; } stopResources(); }
  @SimpleFunction public void Detach() { action(new Runnable() { public void run() { detachView(); brain.pause(SystemClock.uptimeMillis()); } }); }
  @SimpleFunction public void Start() { Enabled(true); }
  @SimpleFunction public void Stop() { Enabled(false); }
  @SimpleFunction public void SetEmotion(final String name) { change(new Runnable() { public void run() { brain.setEmotion(name); brain.lastInteraction = brain.now; brain.nextIdle = brain.now + 8000; } }); }
  @SimpleFunction(description="Temporarily play any named reaction, idle behavior, emotion, system transition or conversation reaction; 250-10000 ms. The base emotion and overlays remain independent.")
  public void PlayReaction(final String name, final int milliseconds) { change(new Runnable() { public void run() { brain.react(name, milliseconds); brain.lastInteraction = brain.now; brain.nextIdle = brain.now + 8000; } }); }
  @SimpleFunction public void StopReaction() { change(new Runnable() { public void run() { brain.stopReaction(); } }); }
  @SimpleFunction public void Sleep() { SetEmotion("sleeping"); }
  @SimpleFunction public void WakeUp() { change(new Runnable() { public void run() { brain.wake(); } }); }
  @SimpleFunction public void SetSystemOverlay(final String name, final boolean active) { change(new Runnable() { public void run() { brain.overlay(name, active, false); } }); }
  @SimpleFunction(description="Set an AI activity or conversation reaction, or empty to clear. Wire this to your AI events; no AI provider is bundled.")
  public void SetConversationState(final String name) { change(new Runnable() { public void run() { brain.converse(name); } }); }
  @SimpleFunction(description="Audio amplitude 0-100 drives mouth opening while speaking. Pass -1 for synthetic talking motion. This is amplitude animation, not phoneme lip sync.")
  public void SpeechLevel(final double level) { change(new Runnable() { public void run() { brain.speechLevel = Double.isNaN(level) || Double.isInfinite(level) ? -1 : Math.max(-1, Math.min(100, level)); } }); }
  @SimpleFunction(description="Display a supplied battery level and plug state. Disable DeviceMonitoring for simulated testing or external power sources.")
  public void SetPowerState(final int level, final boolean plugged) { change(new Runnable() { public void run() { brain.power(level, plugged); if (autoReactions) brain.react(plugged ? "plugged_in" : "unplugged", 1600); } }); }
  @SimpleFunction public YailList Emotions() { return YailList.makeList(EMOTIONS.clone()); }
  @SimpleFunction public YailList Reactions() { return YailList.makeList(REACTIONS.clone()); }
  @SimpleFunction public YailList SystemStates() { return YailList.makeList(SYSTEMS.clone()); }
  @SimpleFunction public YailList ConversationReactions() { return YailList.makeList(CONVERSATIONS.clone()); }
  @SimpleFunction public YailList IdleBehaviors() { return YailList.makeList(IDLES.clone()); }
  @SimpleFunction public String CurrentEmotion() { return brain.emotion; }
  @SimpleFunction public boolean MotionSensorAvailable() { return accelerometer != null; }
  @SimpleFunction public int BatteryLevel() { return brain.batteryLevel; }
  @SimpleFunction public boolean IsPluggedIn() { return brain.plugged; }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean Enabled() { return enabled; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void Enabled(final boolean value) { action(new Runnable() { public void run() { enabled = value; refresh(); } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean Autonomous() { return brain.autonomous; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void Autonomous(final boolean value) { change(new Runnable() { public void run() { brain.autonomous = value; brain.lastInteraction = brain.now; brain.nextIdle = brain.now + 8000; } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean NaturalBlink() { return brain.naturalBlink; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void NaturalBlink(final boolean value) { change(new Runnable() { public void run() { brain.naturalBlink = value; } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean AutoReactions() { return autoReactions; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void AutoReactions(final boolean value) { action(new Runnable() { public void run() { autoReactions = value; } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean SensorsEnabled() { return sensorsEnabled; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void SensorsEnabled(final boolean value) { action(new Runnable() { public void run() { sensorsEnabled = value; refresh(); } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean DeviceMonitoring() { return deviceMonitoring; }
  @DesignerProperty(editorType="boolean", defaultValue="True") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void DeviceMonitoring(final boolean value) { action(new Runnable() { public void run() { deviceMonitoring = value; if (!value) wifiKnown = false; if (!value) { for (String state : SYSTEMS) brain.overlay(state, false, true); brain.plugged = false; brain.batteryLevel = -1; } refresh(); emit(); } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public int FramesPerSecond() { return fps; }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public boolean KeepScreenOn() { return keepScreenOn; }
  @DesignerProperty(editorType="boolean", defaultValue="False") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void KeepScreenOn(final boolean value) { action(new Runnable() { public void run() { keepScreenOn = value; refresh(); } }); }
  @DesignerProperty(editorType="integer", defaultValue="30") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void FramesPerSecond(final int value) { action(new Runnable() { public void run() { fps = Math.max(10, Math.min(60, value)); } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public int SleepAfterSeconds() { return brain.sleepAfterSeconds; }
  @DesignerProperty(editorType="integer", defaultValue="120") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void SleepAfterSeconds(final int value) { change(new Runnable() { public void run() { brain.sleepAfterSeconds = Math.max(0, Math.min(3600, value)); } }); }
  @SimpleProperty(category=PropertyCategory.BEHAVIOR) public double ShakeThreshold() { return shakeThreshold; }
  @DesignerProperty(editorType="float", defaultValue="9.5") @SimpleProperty(category=PropertyCategory.BEHAVIOR) public void ShakeThreshold(final double value) { action(new Runnable() { public void run() { if (!Double.isNaN(value)) shakeThreshold = Math.max(4, Math.min(30, value)); } }); }

  @SimpleEvent public void EmotionChanged(String emotion) { EventDispatcher.dispatchEvent(this, "EmotionChanged", emotion); }
  @SimpleEvent public void ReactionStarted(String reaction) { EventDispatcher.dispatchEvent(this, "ReactionStarted", reaction); }
  @SimpleEvent public void ReactionFinished(String reaction) { EventDispatcher.dispatchEvent(this, "ReactionFinished", reaction); }
  @SimpleEvent public void IdleBehaviorChanged(String behavior) { EventDispatcher.dispatchEvent(this, "IdleBehaviorChanged", behavior); }
  @SimpleEvent public void SystemOverlayChanged(String name, boolean active) { EventDispatcher.dispatchEvent(this, "SystemOverlayChanged", name, active); }
  @SimpleEvent public void Touched(float x, float y) { EventDispatcher.dispatchEvent(this, "Touched", x, y); }
  @SimpleEvent public void Dragged(float x, float y) { EventDispatcher.dispatchEvent(this, "Dragged", x, y); }
  @SimpleEvent public void LongPressed(float x, float y) { EventDispatcher.dispatchEvent(this, "LongPressed", x, y); }
  @SimpleEvent public void Lifted() { EventDispatcher.dispatchEvent(this, "Lifted"); }
  @SimpleEvent public void Shaken(double strength) { EventDispatcher.dispatchEvent(this, "Shaken", strength); }
  @SimpleEvent public void ChargingChanged(boolean plugged, int level) { EventDispatcher.dispatchEvent(this, "ChargingChanged", plugged, level); }
  @SimpleEvent public void BatteryLevelChanged(int level) { EventDispatcher.dispatchEvent(this, "BatteryLevelChanged", level); }
  @SimpleEvent public void WifiChanged(boolean connected) { EventDispatcher.dispatchEvent(this, "WifiChanged", connected); }
  @SimpleEvent public void Error(String message) { if (!disposed) EventDispatcher.dispatchEvent(this, "Error", message == null ? "Unknown error" : message); }
  private boolean emitting;
  private final Runnable eventDrain = new Runnable() { public void run() { if (!disposed) emit(); } };
  private void emit() {
    if (emitting) return;
    emitting = true;
    try {
    int budget = 16;
    while (!brain.events.isEmpty() && !disposed && budget-- > 0) {
      String[] event = brain.events.removeFirst(); String kind = event[0], value = event[1];
      if (kind.equals("emotion")) { if (avatar != null) avatar.setContentDescription("Desk buddy: " + value); EmotionChanged(value); }
      else if (kind.equals("reaction")) ReactionStarted(value);
      else if (kind.equals("finished")) ReactionFinished(value);
      else if (kind.equals("idle")) IdleBehaviorChanged(value);
      else if (kind.equals("battery")) BatteryLevelChanged(Integer.parseInt(value));
      else if (kind.equals("charging")) ChargingChanged(Boolean.parseBoolean(value), brain.batteryLevel);
      else if (kind.equals("overlay")) { int split = value.indexOf(':'); SystemOverlayChanged(value.substring(0, split), Boolean.parseBoolean(value.substring(split + 1))); }
    }
    } finally { emitting = false; }
    if (!disposed && !brain.events.isEmpty()) { ui.removeCallbacks(eventDrain); ui.postDelayed(eventDrain, 100); }
  }
  private void scheduleFrame(long delay) { ui.removeCallbacks(frame); if (running()) ui.postDelayed(frame, delay); }
  private void refresh() {
    ui.removeCallbacks(frame);
    if (!running()) { brain.pause(SystemClock.uptimeMillis()); stopResources(); if (avatar != null) { avatar.cancelTouch(); avatar.setKeepScreenOn(false); } return; }
    avatar.setKeepScreenOn(keepScreenOn);
    brain.resume(SystemClock.uptimeMillis());
    if (sensorsEnabled && !sensorRegistered && accelerometer != null) { motion.reset(); sensorRegistered = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, ui); }
    else if (!sensorsEnabled && sensorRegistered) { sensors.unregisterListener(this); sensorRegistered = false; }
    if (deviceMonitoring && !receiverRegistered) {
      try { receiverRegistered = true; IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION); form.registerReceiver(receiver, filter); updateWifi(); }
      catch (RuntimeException e) { receiverRegistered = false; Error("Device monitoring: " + e.getMessage()); }
    } else if (!deviceMonitoring && receiverRegistered) { try { form.unregisterReceiver(receiver); } catch (RuntimeException ignored) {} receiverRegistered = false; }
    if (running()) scheduleFrame(0);
  }
  private void stopResources() {
    if (sensorRegistered) { sensors.unregisterListener(this); sensorRegistered = false; }
    if (receiverRegistered) { try { form.unregisterReceiver(receiver); } catch (RuntimeException ignored) {} receiverRegistered = false; }
  }
  private void updateWifi() {
    if (!running() || !deviceMonitoring) return;
    try {
      ConnectivityManager manager = (ConnectivityManager)form.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo info = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
      boolean connected = info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
      boolean wasKnown = wifiKnown, changed = !wifiKnown || wifiConnected != connected;
      wifiKnown = true; wifiConnected = connected;
      brain.overlay("wifi_connected", connected, true); brain.overlay("wifi_disconnected", !connected, true);
      if (changed) { if (autoReactions && wasKnown) brain.react(connected ? "wifi_connected" : "wifi_disconnected", 1600); WifiChanged(connected); }
      emit();
    } catch (RuntimeException e) { Error("Wi-Fi status: " + e.getMessage()); }
  }
  public static final class MotionTracker {
    public double strength;
    long lastSensor = -1000, lastShake = -2000, lastLift = -2000, liftCandidate, lastPeak = -2000;
    boolean ready;
    float gx, gy, gz, px, py, pz;
    public void reset() { ready = false; lastPeak = -2000; liftCandidate = 0; lastSensor = -1000; }
    public String sample(long time, float x, float y, float z, double threshold) {
      if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z) || Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z) || time - lastSensor < 40) return "";
      lastSensor = time;
      if (!ready) { gx = x; gy = y; gz = z; ready = true; return ""; }
      float lx=x-gx,ly=y-gy,lz=z-gz;
      gx=.85f*gx+.15f*x;gy=.85f*gy+.15f*y;gz=.85f*gz+.15f*z;
      strength=Math.sqrt(lx*lx+ly*ly+lz*lz);
      if (strength >= threshold) {
        boolean shake=time-lastPeak<=450 && time-lastShake>=1200 && lx*px+ly*py+lz*pz<0;
        lastPeak=time;px=lx;py=ly;pz=lz;liftCandidate=0;
        if(shake){lastShake=time;return "shake";}
      } else {
        double gravity=Math.sqrt(gx*gx+gy*gy+gz*gz);
        double vertical=gravity>1?(lx*gx+ly*gy+lz*gz)/gravity:0;
        if(vertical>1.8&&time-lastLift>1500)liftCandidate=time;
        if(liftCandidate>0&&time-liftCandidate>120&&time-liftCandidate<800&&strength<1.2){liftCandidate=0;lastLift=time;return "lift";}
        if(liftCandidate>0&&time-liftCandidate>800)liftCandidate=0;
      }
      return "";
    }
  }
  public void onSensorChanged(SensorEvent event) {
    if (!running() || !sensorsEnabled || event.values.length < 3) return;
    long time=SystemClock.uptimeMillis();
    String result=motion.sample(time,event.values[0],event.values[1],event.values[2],shakeThreshold);
    if(result.isEmpty())return;
    brain.now=time;brain.lastInteraction=time;brain.nextIdle=time+8000;if(autoReactions)brain.wake();
    if(result.equals("shake")){if(autoReactions)brain.react("dizzy",2500);Shaken(motion.strength);}
    else {if(autoReactions)brain.react("surprised",1800);Lifted();}
    emit();
  }
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}
  public void onResume() { foreground = true; if (!disposed) refresh(); }
  public void onStop() { foreground = false; ui.removeCallbacks(eventDrain); brain.events.clear(); ui.removeCallbacks(frame); brain.pause(SystemClock.uptimeMillis()); stopResources(); if (avatar != null) { avatar.cancelTouch(); avatar.setKeepScreenOn(false); } }
  public void onDestroy() { dispose(); }
  public void onDelete() { dispose(); }
  private void dispose() { if (disposed) return; disposed = true; detachView(); ui.removeCallbacksAndMessages(null); brain.events.clear(); }

  private final class AvatarView extends View {
    private final Renderer renderer = new Renderer();
    float downX, downY; boolean moved, held; long lastDrag;
    final Runnable longPress = new Runnable() { public void run() { if (!running() || moved) return; held = true; brain.now = SystemClock.uptimeMillis(); if (autoReactions) brain.react("hug", 2200); LongPressed(downX, downY); emit(); } };
    AvatarView(Context context) { super(context); setBackgroundColor(Color.BLACK); setClickable(true); setFocusable(true); setContentDescription("Desk buddy: idle"); }
    protected void onDraw(Canvas canvas) { super.onDraw(canvas); renderer.draw(canvas, getWidth(), getHeight(), brain); }
    void cancelTouch() { ui.removeCallbacks(longPress); held = false; moved = false; brain.touching = false; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
    public boolean performClick() { super.performClick(); if (running()) { brain.now = SystemClock.uptimeMillis(); brain.lastInteraction=brain.now;brain.nextIdle=brain.now+8000; if (autoReactions) { brain.wake(); brain.react("giggle", 1300); } emit(); } return true; }
    public boolean onTouchEvent(MotionEvent event) {
      if (!running()) return false;
      float x = event.getX(), y = event.getY(); brain.now = SystemClock.uptimeMillis();
      brain.lastInteraction = brain.now; brain.nextIdle = brain.now + 8000;
      brain.gazeX = Math.max(-1, Math.min(1, (x / Math.max(1, getWidth()) - .5f) * 2));
      brain.gazeY = Math.max(-1, Math.min(1, (y / Math.max(1, getHeight()) - .5f) * 2));
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { cancelTouch(); brain.touching = true; downX = x; downY = y; if(autoReactions)brain.wake(); Touched(x, y); if (running()) ui.postDelayed(longPress, 650); if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); }
      else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        if (Math.abs(x - downX) + Math.abs(y - downY) > 12 * getResources().getDisplayMetrics().density) { moved = true; ui.removeCallbacks(longPress); }
        if (moved && brain.now - lastDrag > 100) { lastDrag = brain.now; if (autoReactions && !brain.reaction.equals("reacting_to_drag")) brain.react("reacting_to_drag", 1200); Dragged(x, y); }
      } else if (event.getActionMasked() == MotionEvent.ACTION_UP) { brain.touching = false; ui.removeCallbacks(longPress); if (!moved && !held) performClick(); if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
      else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { cancelTouch(); if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); }
      emit(); invalidate(); return true;
    }
  }

  /** Reused Paint/Path/RectF: no bitmaps, WebView, audio engine, frame threads, or per-frame asset decoding. */
  public static final class Renderer {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rect = new RectF();
    final Path path = new Path();
    float unit;
    int eyeFill = 0xFF00E5FF;
    float sin(double x) { return (float)Math.sin(x); }
    String expression(String visual, String base) {
      if (contains(EMOTIONS, visual)) return visual;
      if (in(visual,"understanding explaining remembering")) return "focused";
      if (in(visual,"agreeing encouraging comforting playful laugh giggle clap cheer")) return "happy";
      if (in(visual,"disagreeing suspicious")) return "jealous";
      if (in(visual,"low_battery")) return "sad";
      if (in(visual,"critical_battery battery_draining")) return "sleepy";
      if (in(visual,"wifi_connected")) return "happy";
      if (in(visual,"forgetful uncertain ai_error confused wifi_disconnected")) return "confused";
      if (in(visual,"eureka celebrate fully_charged plugged_in")) return "excited";
      if (in(visual,"apologizing")) return "guilty";
      if (in(visual,"impressed interrupted unplugged notification")) return "surprised";
      if (in(visual,"sarcastic")) return "mischievous";
      if (in(visual,"getting_impatient waiting")) return "bored";
      if (in(visual,"listen_closely camera_active searching")) return "curious";
      return base;
    }
    boolean in(String value, String list) {
      if (value.isEmpty()) return false;
      for (int at=list.indexOf(value);at>=0;at=list.indexOf(value,at+1))
        if ((at==0||list.charAt(at-1)==' ')&&(at+value.length()==list.length()||list.charAt(at+value.length())==' ')) return true;
      return false;
    }
    void color(int value) { paint.setColor(value); paint.setStyle(Paint.Style.FILL); paint.setStrokeWidth(.012f); paint.setStrokeCap(Paint.Cap.ROUND); }
    void line(Canvas c, float x, float y, float a, float b) { paint.setStyle(Paint.Style.STROKE); c.drawLine(x, y, a, b, paint); paint.setStyle(Paint.Style.FILL); }
    void box(Canvas c, float x, float y, float w, float h, float radius) { rect.set(x-w/2,y-h/2,x+w/2,y+h/2); c.drawRoundRect(rect,radius,radius,paint); }
    void circle(Canvas c,float x,float y,float r) { c.drawCircle(x,y,r,paint); }
    void text(Canvas c,String value,float x,float y,float size) { paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER); c.drawText(value,x,y,paint); }
    void heart(Canvas c,float x,float y,float size) {
      path.reset(); path.moveTo(x,y+size*.5f); path.cubicTo(x-size,y,x-size*.65f,y-size,x,y-size*.35f); path.cubicTo(x+size*.65f,y-size,x+size,y,x,y+size*.5f); c.drawPath(path,paint);
    }
    void bolt(Canvas c,float x,float y,float size) { path.reset(); path.moveTo(x+.1f*size,y-.55f*size); path.lineTo(x-.4f*size,y+.05f*size); path.lineTo(x,y+.05f*size); path.lineTo(x-.1f*size,y+.55f*size); path.lineTo(x+.4f*size,y-.05f*size); path.lineTo(x,y-.05f*size); path.close(); c.drawPath(path,paint); }
    void star(Canvas c,float x,float y,float r) { line(c,x-r,y,x+r,y); line(c,x,y-r,x,y+r); line(c,x-r*.6f,y-r*.6f,x+r*.6f,y+r*.6f); line(c,x-r*.6f,y+r*.6f,x+r*.6f,y-r*.6f); }
    void eye(Canvas c,float x,float y,float w,float h,float angle) { c.save(); c.translate(x,y); c.rotate(angle); color(0x1600E5FF); box(c,0,0,w+.055f,h+.055f,.035f); color(0x3800E5FF); box(c,0,0,w+.024f,h+.024f,.025f); color(eyeFill); box(c,0,0,w,Math.max(.009f,h),.018f); c.restore(); }
    void spiral(Canvas c,float x,float y,float t) { path.reset(); for (int i=0;i<70;i++) { double a=i*.19+t; float r=i*.0015f; float px=x+(float)Math.cos(a)*r, py=y+(float)Math.sin(a)*r; if(i==0)path.moveTo(px,py);else path.lineTo(px,py); } paint.setStyle(Paint.Style.STROKE); c.drawPath(path,paint); paint.setStyle(Paint.Style.FILL); }
    public void draw(Canvas c,int width,int height,Brain b) {
      c.drawColor(Color.BLACK); if(width<=0||height<=0)return;
      unit=Math.min(width,height)*.86f; c.save(); c.translate(width*.5f,height*.48f); c.scale(unit,unit);
      String v=b.visual(), e=expression(v,b.emotion);
      eyeFill=in(e,"happy excited proud grateful")?0xFF55EEFF:0xFF00E5FF;
      float t=(b.now%600000L)/1000f, p=(float)b.progress(), fade=b.reaction.isEmpty()?1:(float)Math.sin(Math.PI*p), wave=sin(t*5), dx=0,dy=0,rot=0,scale=1;
      boolean sleeping=e.equals("sleeping")||in(v,"sleep sleeping shutdown");
      if(in(v,"dance dancing_alone celebrate cheer excited jumping exercising")) { dx=.055f*sin(t*7);dy=-.05f*Math.abs(sin(t*9));rot=sin(t*7)*8; }
      if(in(v,"jump excited eureka plugged_in")) dy-=.16f*(float)Math.sin(Math.PI*p);
      if(in(v,"nod agreeing understanding")) dy+=.04f*sin(t*9)*fade;
      if(in(v,"shake_head disagreeing")) {dx+=.07f*sin(t*9)*fade;rot+=sin(t*9)*4;}
      if(in(v,"laugh giggle reacting_to_touch hiccup")) dy+=.026f*sin(t*(v.equals("hiccup")?3:17))*fade;
      if(in(v,"scared angry frustrated crying")) dx+=.008f*sin(t*43);
      if(in(v,"dizzy spin reacting_to_drag fall")) {rot+=v.equals("spin")?360*p:sin(t*5)*23;dy+=v.equals("fall")?.2f*fade:0;scale-=.07f*fade;}
      if(in(v,"head_tilt curious confused uncertain suspicious")||e.equals("confused"))rot+=12*fade*sin(t*2);
      if(in(v,"peek screen_edge_peek")){dx+=.35f*fade;rot-=15*fade;}
      if(in(v,"stretch waking_up startup")){scale+=.1f*fade;dy-=.04f*fade;}
      if(in(v,"get_up")){rot=-35*(1-p);dy=.18f*(1-p);}
      if(in(v,"sneeze")){dy+=.06f*sin(p*14)*fade;rot+=sin(p*14)*12;}
      if(sleeping){rot+=7;dy+=.06f;}
      if(in(e,"sad guilty"))dy+=.025f;
      if(!in(e,"focused sleeping"))scale+=.012f*sin(t*(e.equals("calm")?1.5:2.1));
      c.translate(dx,dy);c.rotate(rot);c.scale(scale,scale);
      float gx=b.gazeX*.028f,gy=b.gazeY*.02f,ew=.215f,eh=.205f,left=0,right=0;
      if(in(v,"look_left look_left_right look_around eye_tracking searching reconnecting connecting")||b.has("connecting")||b.has("reconnecting")||b.has("camera_active"))gx+=.045f*sin(t*1.7);
      if(v.equals("look_right"))gx+=.045f;
      if(in(e,"shy guilty jealous mischievous"))gx+=.035f;
      if(in(e,"sad guilty sleepy bored disgusted frustrated focused"))eh*=.45f;
      if(!b.plugged && b.batteryLevel>=0 && b.batteryLevel<40)eh*=.65f+.35f*b.batteryLevel/40;
      if(in(e,"surprised excited curious scared"))eh*=1.22f;
      if(in(e,"angry frustrated")){left=-19;right=19;eh*=.7f;}
      if(in(e,"sad guilty")){left=13;right=-13;gy+=.02f;}
      if(in(e,"jealous mischievous sarcastic")){left=10;right=-4;}
      if(e.equals("disgusted")){left=15;right=6;gx+=.04f;}
      if(e.equals("curious")){left=-8;right=8;}
      float blink=b.naturalBlink&&b.now-b.blinkStart>=0&&b.now-b.blinkStart<160?.025f:1;
      if(v.equals("yawn"))blink*=.08f+.92f*(1-fade);
      if(v.equals("random_blink"))blink=sin(t*10)>.55?.03f:1;
      if(sleeping)blink=.025f;
      color(0xFF00E5FF);
      if(e.equals("love")){heart(c,-.18f+gx,-.04f+gy,.15f);heart(c,.18f+gx,-.04f+gy,.15f);}
      else if(in(v,"dizzy ai_error reacting_to_drag")){spiral(c,-.18f,-.04f,t*3);spiral(c,.18f,-.04f,-t*3);}
      else {eye(c,-.18f+gx,-.04f+gy,ew,eh*blink,left);eye(c,.18f+gx,-.04f+gy,ew*(e.equals("curious")?.85f:1),eh*blink*(v.equals("wink")&&p>.2&&p<.8?.04f:1),right);}
      color(0xFF00E5FF);
      if(in(e,"angry frustrated proud mischievous focused suspicious")){line(c,-.29f,-.21f,-.11f,-.18f+(e.equals("angry")?.045f:0));line(c,.11f,-.18f+(e.equals("angry")?.045f:0),.29f,-.21f);}
      float mouthY=.19f;
      boolean talk=b.has("speaking")||v.equals("speaking")||in(v,"storytelling explaining");
      if(talk){float level=b.speechLevel<0?.5f+.5f*sin(t*17):(float)b.speechLevel/100;box(c,0,mouthY,.105f,.015f+level*.075f,.02f);}
      else if(in(v,"yawn sneeze surprised scared sleepy")){box(c,0,mouthY,.065f,.04f+.07f*Math.abs(wave),.025f);}
      else if(in(e,"happy excited love grateful proud")||in(v,"laugh giggle encouraging comforting impressed celebrate")){paint.setStyle(Paint.Style.STROKE);rect.set(-.09f,mouthY-.07f,.09f,mouthY+.04f);c.drawArc(rect,0,180,false,paint);paint.setStyle(Paint.Style.FILL);}
      else if(in(e,"sad crying guilty frustrated disgusted")){paint.setStyle(Paint.Style.STROKE);rect.set(-.075f,mouthY,.075f,mouthY+.07f);c.drawArc(rect,180,180,false,paint);paint.setStyle(Paint.Style.FILL);}
      else if(in(e,"jealous mischievous")){line(c,-.06f,mouthY,.07f,mouthY-.02f);}
      else line(c,-.055f,mouthY,.055f,mouthY);
      if(in(e,"shy embarrassed grateful")){color(0x5000E5FF);box(c,-.31f,.105f,.085f,.033f,.015f);box(c,.31f,.105f,.085f,.033f,.015f);color(0xFF00E5FF);}
      if(e.equals("crying")){for(int i=0;i<2;i++){float y=.085f+((t*.15f+i*.14f)%.25f);circle(c,i==0?-.18f:.18f,y,.012f);}}
      props(c,v,e,t,p,fade,sleeping);
      if(b.has("processing")&&!v.equals("processing"))text(c,"...",.30f,-.32f,.12f);
      if(b.has("microphone_muted")){line(c,0,.16f,0,.26f);circle(c,0,.265f,.015f);}
      // Power is attached to the face transform, independent of mood/reaction.
      if(b.plugged||b.has("charging")||b.has("fully_charged")||in(v,"charging plugged_in fully_charged")){color(0xFF00E5FF);bolt(c,.365f,-.24f,.09f+.01f*sin(t*6));if(b.has("charging")||v.equals("charging")){paint.setStyle(Paint.Style.STROKE);circle(c,.365f,-.24f,.075f+.01f*sin(t*3));paint.setStyle(Paint.Style.FILL);}}
      c.restore(); // screen-space badges remain readable when the avatar spins.
      c.save();c.translate(width*.5f,height*.48f);c.scale(unit,unit);overlays(c,b,v,t);c.restore();
    }
    void props(Canvas c,String v,String e,float t,float p,float fade,boolean asleep) {
      color(0xFF00E5FF);
      if(asleep){text(c,"z",.22f,-.27f,.075f);text(c,"Z",.30f,-.36f,.09f);}
      if(in(v,"wave cheer celebrate attention_seeking goodbye shutdown")){line(c,.34f,.14f,.43f,-.02f);line(c,.43f,-.02f,.43f+.065f*sin(t*9),-.19f);circle(c,.43f+.065f*sin(t*9),-.19f,.025f);}
      if(in(v,"clap grateful hug shrug comforting stretch waking_up startup")) {
        float gap=v.equals("clap")?.06f+.06f*Math.abs(sin(t*12)):v.equals("grateful")?.025f:.29f;
        float y=in(v,"stretch waking_up startup")?-.25f:v.equals("shrug")?.06f:.20f;
        line(c,-.32f,.15f,-gap,y);line(c,.32f,.15f,gap,y);circle(c,-gap,y,.025f);circle(c,gap,y,.025f);
      }
      if(in(v,"facepalm embarrassed")){line(c,-.33f,.18f,-.07f,-.03f);box(c,-.07f,-.03f,.18f,.12f,.03f);}
      if(in(v,"point explaining")){line(c,.32f,.13f,.48f,.06f);line(c,.48f,.06f,.56f,-.02f);}
      if(in(v,"think confused remembering uncertain forgetful suspicious searching processing")){text(c,in(v,"confused forgetful uncertain")?"?":"...",.30f,-.32f,.12f);if(v.equals("confused")){float scratch=-.32f+.012f*sin(t*9);line(c,-.31f,.15f,scratch,-.12f);circle(c,scratch,-.12f,.025f);}else{line(c,-.31f,.15f,-.15f,.23f);circle(c,-.15f,.23f,.025f);}}
      if(in(v,"love blow_kiss shy")){heart(c,.2f*sin(t*2),-.24f-.13f*(v.equals("love")?(t*.4f)%1:p),.065f);if(v.equals("love"))heart(c,-.3f,.15f,.05f);}
      if(in(v,"excited celebrate fully_charged eureka impressed")){for(int i=0;i<6;i++){double a=i*Math.PI/3+t;star(c,(float)Math.cos(a)*.38f,(float)Math.sin(a)*.31f,.017f);}}
      if(in(v,"foot_tapping frustrated getting_impatient waiting")){line(c,-.19f,.32f,-.12f,.32f+.018f*sin(t*10));line(c,.12f,.32f,.20f,.32f);}
      if(in(v,"whistling humming")){text(c,"♪",.26f,-.25f-.025f*sin(t*3),.09f);}
      if(v.equals("daydreaming")){paint.setStyle(Paint.Style.STROKE);circle(c,.27f,-.28f,.06f);circle(c,.20f,-.19f,.02f);paint.setStyle(Paint.Style.FILL);text(c,"*",.27f,-.255f,.07f);}
      if(in(v,"reading storytelling")){paint.setStyle(Paint.Style.STROKE);box(c,0,.31f,.29f,.13f,.012f);line(c,0,.25f,0,.375f);line(c,-.12f,.285f,-.035f,.285f);line(c,.035f,.32f,.12f,.32f);paint.setStyle(Paint.Style.FILL);}
      if(v.equals("gaming")){paint.setStyle(Paint.Style.STROKE);box(c,0,.31f,.26f,.12f,.04f);paint.setStyle(Paint.Style.FILL);line(c,-.09f,.31f,-.035f,.31f);line(c,-.06f,.285f,-.06f,.335f);circle(c,.07f,.30f,.012f);circle(c,.10f,.325f,.012f);}
      if(v.equals("drinking_coffee")){float y=.28f-.09f*Math.abs(sin(t*2));paint.setStyle(Paint.Style.STROKE);box(c,.11f,y,.09f,.09f,.015f);circle(c,.17f,y,.025f);paint.setStyle(Paint.Style.FILL);text(c,"~",.11f,y-.06f,.05f);}
      if(v.equals("eating")){circle(c,.10f,.29f-.06f*Math.abs(sin(t*5)),.035f);circle(c,.15f,.30f,.007f);}
      if(in(v,"playing catching_falling_object")){float x=.26f*sin(t*2.5),y=v.equals("playing")?.30f-.12f*Math.abs(sin(t*4)):-.3f+.6f*p;circle(c,x,y,.037f);paint.setStyle(Paint.Style.STROKE);circle(c,x,y,.044f);paint.setStyle(Paint.Style.FILL);}
      if(v.equals("chasing_butterfly")){float x=.32f*sin(t*2),y=-.25f+.06f*sin(t*5);circle(c,x-.018f,y,.025f);circle(c,x+.018f,y,.025f);line(c,x,y-.02f,x,y+.02f);}
      if(in(v,"exercising proud")){line(c,-.31f,.14f,-.41f,.27f);line(c,.31f,.14f,.41f,.27f);line(c,-.18f,.33f,-.25f,.4f);line(c,.18f,.33f,.25f,.4f);}
      if(in(v,"apologizing guilty")){line(c,-.27f,.19f,-.02f,.27f);line(c,.27f,.19f,.02f,.27f);}
      if(v.equals("getting_impatient")){line(c,-.31f,.15f,.16f,.27f);line(c,.31f,.15f,-.16f,.27f);}
      if(v.equals("disgusted")){text(c,"~",.30f,.05f,.09f);}
      if(v.equals("listen_closely")){paint.setStyle(Paint.Style.STROKE);rect.set(-.42f,-.15f,-.26f,.1f);c.drawArc(rect,110,140,false,paint);paint.setStyle(Paint.Style.FILL);}
    }
    void overlays(Canvas c,Brain b,String v,float t) {
      boolean critical=b.has("critical_battery")||v.equals("critical_battery"), low=b.has("low_battery")||v.equals("low_battery"), full=b.has("fully_charged")||v.equals("fully_charged");
      if(b.batteryLevel>=0||critical||low||full){color(critical||low?0xFFFF4865:full?0xFF57F28B:0xFF00E5FF);if(!critical||sin(t*5)>-.4){paint.setStyle(Paint.Style.STROKE);box(c,0,.43f,.18f,.07f,.009f);paint.setStyle(Paint.Style.FILL);box(c,.10f,.43f,.018f,.029f,.003f);float level=b.batteryLevel<0?(full?1:.1f):b.batteryLevel/100f;box(c,-.074f+.074f*level,.43f,.148f*level,.043f,.003f);}}
      color(0xFF00E5FF);
      if(b.has("listening")||v.equals("listening")){for(int i=0;i<4;i++){float h=.025f+.065f*Math.abs(sin(t*7+i));box(c,-.055f+i*.037f,.33f,.016f,h,.007f);}}
      if(b.has("microphone_muted")){circle(c,-.36f,.28f,.035f);line(c,-.39f,.24f,-.32f,.32f);}
      if(b.has("camera_active")){paint.setStyle(Paint.Style.STROKE);box(c,.34f,.27f,.09f,.055f,.009f);circle(c,.34f,.27f,.016f);paint.setStyle(Paint.Style.FILL);}
      if(b.has("wifi_connected")||b.has("wifi_disconnected")||in(v,"wifi_connected wifi_disconnected")){boolean off=b.has("wifi_disconnected")||v.equals("wifi_disconnected");paint.setStyle(Paint.Style.STROKE);for(int i=1;i<=3;i++){rect.set(-.38f-i*.018f,-.29f-i*.018f,-.38f+i*.018f,-.29f+i*.018f);c.drawArc(rect,220,100,false,paint);}paint.setStyle(Paint.Style.FILL);if(off)line(c,-.44f,-.34f,-.32f,-.23f);}
      if(b.has("processing")||b.has("connecting")||b.has("reconnecting")||in(v,"processing connecting reconnecting")){for(int i=0;i<3;i++)circle(c,-.04f+i*.04f,.31f,.008f+.003f*sin(t*6-i));}
      if(b.has("notification")||v.equals("notification"))text(c,"!",.36f,.08f,.13f);
      if(b.has("ai_error")||v.equals("ai_error"))text(c,"?",-.36f,.08f,.13f);
      if(b.has("update")||v.equals("update")){line(c,.30f,.29f,.40f+.02f*sin(t*5),.39f);circle(c,.30f,.29f,.025f);}
      if(b.has("battery_draining"))text(c,"↓",-.15f,.445f,.07f);
    }
  }
}
