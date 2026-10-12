import com.fabiyon.deskbuddy.DeskBuddy;
import com.fabiyon.deskbuddy.DeskBuddy.Brain;
import com.fabiyon.deskbuddy.DeskBuddy.MotionTracker;
import android.graphics.Canvas;
import java.nio.file.*;
import java.util.*;

/** Production bytecode with a vector Canvas test double, not Android device execution. */
public final class DeskBuddyTests {
  static int passed;
  static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  interface Case { void run() throws Exception; }
  static void test(String name, Case task) throws Exception { task.run(); passed++; System.out.println("PASS " + name); }
  public static void main(String[] args) throws Exception {
    final Path gallery=Paths.get(args[0]);Files.createDirectories(gallery);
    test("layers preserve sad base, temporary wave and charging",new Case(){public void run(){Brain b=new Brain(0);b.setEmotion("sad");b.overlay("charging",true,false);b.react("wave",2000);b.advance(1000);check(b.emotion.equals("sad")&&b.reaction.equals("wave")&&b.has("charging"),"Layer overwrite");b.advance(2000);check(b.reaction.isEmpty()&&b.emotion.equals("sad")&&b.has("charging"),"Reaction failed to restore base");}});
    test("wave drawing retains the sad face expression",new Case(){public void run()throws Exception{java.lang.reflect.Method m=DeskBuddy.Renderer.class.getDeclaredMethod("expression",String.class,String.class);m.setAccessible(true);check(m.invoke(new DeskBuddy.Renderer(),"wave","sad").equals("sad"),"Wave replaced sadness");}});
    test("replacement reactions finish old reaction exactly once",new Case(){public void run(){Brain b=new Brain(0);b.react("wave",1000);b.react("wink",1000);int count=0;for(String[] e:b.events)if(e[0].equals("finished")&&e[1].equals("wave"))count++;check(count==1,"Duplicate finish");}});
    test("sleep wake restores selected base",new Case(){public void run(){Brain b=new Brain(0);b.setEmotion("calm");b.setEmotion("sleeping");b.now=500;b.wake();check(b.emotion.equals("calm")&&b.reaction.equals("waking_up"),"Wake mismatch");}});
    test("automatic sleep follows inactivity and respects zero timeout",new Case(){public void run(){Brain b=new Brain(0);b.sleepAfterSeconds=2;b.advance(2100);check(b.emotion.equals("sleeping"),"Sleep absent");Brain c=new Brain(0);c.sleepAfterSeconds=0;c.advance(999999);check(!c.emotion.equals("sleeping"),"Disabled timeout slept");}});
    test("AI listening prevents autonomous sleep",new Case(){public void run(){Brain b=new Brain(0);b.sleepAfterSeconds=1;b.converse("listening");b.advance(5000);check(!b.emotion.equals("sleeping")&&b.has("listening"),"Slept during conversation");}});
    test("active touch prevents autonomous sleep",new Case(){public void run(){Brain b=new Brain(0);b.sleepAfterSeconds=1;b.touching=true;b.advance(10000);check(!b.emotion.equals("sleeping"),"Slept while held");}});
    test("background pause freezes reaction and idle deadlines",new Case(){public void run(){Brain b=new Brain(0);b.react("wave",2000);b.pause(500);b.advance(10000);check(b.reaction.equals("wave"),"Background advanced");b.resume(10000);b.advance(11000);check(b.reaction.equals("wave"),"Paused reaction expired");b.advance(11500);check(b.reaction.isEmpty(),"Resumed reaction stuck");}});
    test("manual overlay survives automatic source removal",new Case(){public void run(){Brain b=new Brain(0);b.overlay("charging",true,true);b.overlay("charging",true,false);b.overlay("charging",false,true);check(b.has("charging"),"Manual state lost");b.overlay("charging",false,false);check(!b.has("charging"),"Overlay removal absent");}});
    test("power state clamps and low critical full flags are exclusive",new Case(){public void run(){Brain b=new Brain(0);b.power(4,false);check(b.has("critical_battery")&&!b.has("low_battery"),"Critical overlap");b.power(15,false);check(b.has("low_battery")&&!b.has("critical_battery"),"Low overlap");b.power(500,true);check(b.batteryLevel==100&&b.has("fully_charged")&&!b.has("charging"),"Full state wrong");b.power(-3,false);check(b.batteryLevel==0,"Negative power");}});
    test("draining energy clears when charger connects",new Case(){public void run(){Brain b=new Brain(0);b.power(60,false);b.power(59,false);check(b.has("battery_draining"),"No draining state");b.power(59,true);check(!b.has("battery_draining")&&b.has("charging"),"Draining persisted");}});
    test("invalid names fail without modifying base",new Case(){public void run(){Brain b=new Brain(0);try{b.setEmotion("typo");throw new AssertionError("Accepted invalid emotion");}catch(IllegalArgumentException expected){}check(b.emotion.equals("idle"),"Invalid emotion changed state");try{b.react("typo",1000);throw new AssertionError("Accepted invalid reaction");}catch(IllegalArgumentException expected){}}});
    test("callback backlog stays bounded",new Case(){public void run(){Brain b=new Brain(0);for(int i=0;i<1000;i++)b.react("wave",1000);check(b.events.size()<=64,"Unbounded events");}});
    test("autonomous idle chooses valid varied behaviors",new Case(){public void run(){Brain b=new Brain(0);b.sleepAfterSeconds=0;Set<String> names=new HashSet<String>();for(int i=1;i<70;i++){b.advance(i*20000L);names.add(b.reaction);}check(names.size()>8,"Idle lacks variation");}});
    test("stationary accelerometer emits no lift or shake",new Case(){public void run(){MotionTracker m=new MotionTracker();for(int i=0;i<100;i++)check(m.sample(i*40,0,0,9.8f,9.5).isEmpty(),"False motion");}});
    test("opposite shake peaks and cooldown",new Case(){public void run(){MotionTracker m=new MotionTracker();m.sample(0,0,0,9.8f,9.5);check(m.sample(100,20,0,9.8f,9.5).isEmpty(),"Single peak shake");check(m.sample(180,-20,0,9.8f,9.5).equals("shake"),"No shake");check(m.sample(260,20,0,9.8f,9.5).isEmpty(),"Cooldown missing");}});
    test("smooth orientation change is not a repeated shake",new Case(){public void run(){MotionTracker m=new MotionTracker();m.sample(0,0,0,9.8f,9.5);for(int i=1;i<8;i++)check(!m.sample(i*40,9.8f,0,0,9.5).equals("shake"),"Orientation treated as shake");}});
    test("upward impulse then settling infers lift",new Case(){public void run(){MotionTracker m=new MotionTracker();m.sample(0,0,0,9.8f,9.5);m.sample(100,0,0,12.8f,9.5);check(m.sample(260,0,0,10.5f,9.5).equals("lift"),"Lift not inferred");}});
    test("motion reset ignores old peaks and invalid numbers",new Case(){public void run(){MotionTracker m=new MotionTracker();m.sample(0,0,0,9.8f,9.5);m.sample(100,20,0,9.8f,9.5);m.reset();check(m.sample(150,0,0,9.8f,9.5).isEmpty(),"Reset event");check(m.sample(190,Float.NaN,0,0,9.5).isEmpty(),"NaN motion");}});
    test("icon switches are independent and reversible without losing monitored state",new Case(){public void run(){Brain b=new Brain(0);b.power(65,false);b.overlay("wifi_connected",true,true);DeskBuddy.Renderer r=new DeskBuddy.Renderer();Canvas both=new Canvas(480,400);r.draw(both,480,400,b);b.showBatteryIcon=false;Canvas wifi=new Canvas(480,400);r.draw(wifi,480,400,b);b.showWifiIcon=false;Canvas neither=new Canvas(480,400);r.draw(neither,480,400,b);check(both.shapes>wifi.shapes&&wifi.shapes>neither.shapes,"Symbols did not hide independently");check(b.batteryLevel==65&&b.has("wifi_connected"),"Visibility discarded monitored state");b.showBatteryIcon=true;b.showWifiIcon=true;Canvas restored=new Canvas(480,400);r.draw(restored,480,400,b);check(restored.result().equals(both.result()),"Re-enabling did not restore symbols");}});
    test("hidden icons also cover manual warnings and temporary Wi-Fi previews",new Case(){public void run(){Brain b=new Brain(0);b.power(4,false);b.overlay("battery_draining",true,false);b.react("wifi_disconnected",2000);DeskBuddy.Renderer r=new DeskBuddy.Renderer();Canvas visible=new Canvas(480,400);r.draw(visible,480,400,b);b.showBatteryIcon=false;b.showWifiIcon=false;Canvas hidden=new Canvas(480,400);r.draw(hidden,480,400,b);check(hidden.shapes<visible.shapes&&!hidden.result().contains("↓"),"Warnings or preview symbols remained");check(b.has("critical_battery")&&b.has("battery_draining")&&b.reaction.equals("wifi_disconnected"),"Reaction state changed");}});
    test("hiding battery retains the attached charger power animation",new Case(){public void run(){Brain b=new Brain(0);b.power(65,true);b.showBatteryIcon=false;Canvas charging=new Canvas(480,400);new DeskBuddy.Renderer().draw(charging,480,400,b);b.power(65,false);Canvas unplugged=new Canvas(480,400);new DeskBuddy.Renderer().draw(unplugged,480,400,b);check(charging.shapes>unplugged.shapes,"Hidden battery also removed charger bolt");}});
    String[][] catalogs={DeskBuddy.EMOTIONS,DeskBuddy.REACTIONS,DeskBuddy.SYSTEMS,DeskBuddy.CONVERSATIONS,DeskBuddy.IDLES};
    String[] groups={"emotions","reactions","systems","conversation","idle"};
    StringBuilder index=new StringBuilder("<!doctype html><meta charset=utf-8><title>Desk Buddy drawing gallery</title><style>body{background:#05080a;color:#00e5ff;font:16px system-ui;margin:24px}input{padding:10px;background:#122027;color:white;border:1px solid cyan}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px}article{border:1px solid #16434c;border-radius:12px;padding:12px}img{width:100%;background:black}small{color:#8bafba}</style><h1>Desk Buddy</h1><p>Sample poses from the production renderer using a vector Canvas test double. Android rendering, sensors and animation performance require device testing.</p><input placeholder='Filter emotions or reactions' oninput=\"for(const c of document.querySelectorAll('article'))c.hidden=!c.textContent.includes(this.value.toLowerCase())\"><main>");
    for(int g=0;g<catalogs.length;g++)for(final String name:catalogs[g]){
      final int group=g;
      test("render "+groups[g]+" / "+name,new Case(){public void run()throws Exception{
        Brain b=new Brain(0);b.autonomous=false;b.naturalBlink=false;
        if(group==0)b.setEmotion(name);else if(group==2){b.overlay(name,true,false);b.react(name,4000);}else b.react(name,4000);
        b.advance(1500);Canvas c=new Canvas(480,400);new DeskBuddy.Renderer().draw(c,480,400,b);String svg=c.result();
        check(c.shapes>=4&&c.shapes<200,"Invalid drawing budget: "+name);check(svg.contains("#000000")&&svg.contains("#00e5ff"),"Missing avatar colors");
        Files.write(gallery.resolve(groups[group]+"-"+name+".svg"),svg.getBytes("UTF-8"));
      }});
      index.append("<article><img alt='"+name+"' src='"+groups[g]+"-"+name+".svg'><b>"+name+"</b><br><small>"+groups[g]+"</small></article>");
    }
    Files.write(gallery.resolve("index.html"),index.append("</main>").toString().getBytes("UTF-8"));
    System.out.println("DeskBuddy tests: "+passed+" passed, 0 failed");
  }
}
