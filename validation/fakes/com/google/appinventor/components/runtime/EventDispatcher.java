package com.google.appinventor.components.runtime;
import java.util.*;
public final class EventDispatcher {
  public static final List<Event> events = Collections.synchronizedList(new ArrayList<Event>());
  public static final class Event {
    public final String name, thread;
    public final Object[] arguments;
    Event(String name, Object[] arguments) {
      this.name = name; this.arguments = arguments; this.thread = Thread.currentThread().getName();
    }
  }
  public static boolean dispatchEvent(Component component, String name, Object... arguments) {
    events.add(new Event(name, arguments)); return true;
  }
  public static int count(String name) {
    int count = 0;
    synchronized (events) { for (Event event : events) if (event.name.equals(name)) count++; }
    return count;
  }
  public static void reset() { events.clear(); }
}
