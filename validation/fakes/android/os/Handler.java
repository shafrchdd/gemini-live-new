package android.os;
import java.util.concurrent.*;
import java.util.*;
public final class Handler {
  private static final ScheduledThreadPoolExecutor UI = new ScheduledThreadPoolExecutor(1,
      new ThreadFactory() { public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "fake-main"); thread.setDaemon(true); return thread;
      }});
  private final Map<Runnable, List<Future<?>>> tasks = new ConcurrentHashMap<Runnable, List<Future<?>>>();
  public Handler() {}
  public Handler(Looper looper) {}
  public boolean post(Runnable runnable) { return postDelayed(runnable, 0); }
  public boolean postDelayed(Runnable runnable, long delay) {
    Future<?> future = UI.schedule(runnable, delay, TimeUnit.MILLISECONDS);
    synchronized (tasks) {
      List<Future<?>> futures = tasks.get(runnable);
      if (futures == null) { futures = new ArrayList<Future<?>>(); tasks.put(runnable, futures); }
      futures.add(future);
    }
    return true;
  }
  public void removeCallbacks(Runnable runnable) {
    synchronized (tasks) {
      List<Future<?>> futures = tasks.remove(runnable);
      if (futures != null) for (Future<?> future : futures) future.cancel(false);
    }
  }
  public void removeCallbacksAndMessages(Object token) {
    synchronized (tasks) {
      for (List<Future<?>> futures : tasks.values()) for (Future<?> future : futures) future.cancel(false);
      tasks.clear();
    }
  }
  public static void runSync(Runnable runnable) throws Exception { UI.submit(runnable).get(3, TimeUnit.SECONDS); }
  public static void awaitIdle() throws Exception { runSync(new Runnable() { public void run() {} }); }
}
