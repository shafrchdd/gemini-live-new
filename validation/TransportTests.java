import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Focused, standalone tests for GeminiLive.Transport; uses no Android runtime. */
public final class TransportTests {
  private static int passed;
  private static int failed;
  private static Class<?> transportClass;
  private static Constructor<?> constructor;
  private static Method readMessage;
  private static Method sendText;
  private static Method close;
  private static Method getCloseReason;
  private static Method verifyUpgrade;
  private static Method readHttpHeaders;
  private static Field input;
  private static Field output;
  private static Field closed;

  private interface Test { void run() throws Exception; }

  public static void main(String[] args) throws Exception {
    transportClass = Class.forName(args.length == 0 ? "TransportTestTarget$Transport" : args[0]);
    constructor = transportClass.getDeclaredConstructor();
    constructor.setAccessible(true);
    readMessage = method("readMessage");
    sendText = method("sendText", String.class);
    close = method("close");
    getCloseReason = method("getCloseReason");
    verifyUpgrade = method("verifyUpgrade", String.class, String.class);
    readHttpHeaders = method("readHttpHeaders", java.io.InputStream.class);
    input = field("input");
    output = field("output");
    closed = field("closed");

    test("plain UTF-8 text", new Test() { public void run() throws Exception {
      assertEquals("hello €", read(frame(true, 1, "hello €".getBytes("UTF-8"))));
    }});
    test("binary UTF-8 JSON", new Test() { public void run() throws Exception {
      assertEquals("{\"setupComplete\":{}}", read(frame(true, 2, "{\"setupComplete\":{}}".getBytes("UTF-8"))));
    }});
    test("UTF-8 character split across fragments", new Test() { public void run() throws Exception {
      byte[] text = "A\uD83D\uDE00B".getBytes("UTF-8");
      assertEquals("A\uD83D\uDE00B", read(join(frame(false, 1, Arrays.copyOfRange(text, 0, 3)), frame(true, 0, Arrays.copyOfRange(text, 3, text.length)))));
    }});
    test("empty text", new Test() { public void run() throws Exception {
      assertEquals("", read(frame(true, 1, new byte[0])));
    }});
    test("extended 16-bit incoming length", new Test() { public void run() throws Exception {
      String payload = repeat('x', 126);
      assertEquals(payload, read(frame(true, 1, payload.getBytes("UTF-8"))));
    }});
    test("extended 64-bit incoming length", new Test() { public void run() throws Exception {
      String payload = repeat('y', 65536);
      assertEquals(payload, read(frame(true, 1, payload.getBytes("UTF-8"))));
    }});
    test("ping interleaved within fragmented message", new Test() { public void run() throws Exception {
      ByteArrayOutputStream replies = new ByteArrayOutputStream();
      Object transport = transport(join(frame(false, 1, bytes("hel")), frame(true, 9, bytes("ping")), frame(true, 0, bytes("lo"))), replies);
      assertEquals("hello", call(readMessage, transport));
      Decoded response = decode(replies.toByteArray());
      assertEquals(Integer.valueOf(10), Integer.valueOf(response.opcode));
      assertEquals("ping", new String(response.payload, "UTF-8"));
      assertTrue(response.masked, "client pong must be masked");
    }});
    test("pong does not become application data", new Test() { public void run() throws Exception {
      assertEquals("message", read(join(frame(true, 10, bytes("pong")), frame(true, 1, bytes("message")))));
    }});
    test("server close ends reader and sends masked reply", new Test() { public void run() throws Exception {
      ByteArrayOutputStream replies = new ByteArrayOutputStream();
      byte[] payload = join(new byte[] {3, (byte) 232}, bytes("normal"));
      Object transport = transport(frame(true, 8, payload), replies);
      assertEquals(null, call(readMessage, transport));
      assertTrue(closed.getBoolean(transport), "close frame must close transport");
      String reason = (String) call(getCloseReason, transport);
      assertTrue(reason.indexOf("1000") >= 0 && reason.indexOf("normal") >= 0, "close reason must retain code and text");
      Decoded response = decode(replies.toByteArray());
      assertEquals(Integer.valueOf(8), Integer.valueOf(response.opcode));
      assertTrue(response.masked, "client close reply must be masked");
      assertTrue(Arrays.equals(payload, response.payload), "close reply must echo payload");
    }});
    test("empty server close is accepted", new Test() { public void run() throws Exception {
      Object transport = transport(frame(true, 8, new byte[0]), new ByteArrayOutputStream());
      assertEquals(null, call(readMessage, transport));
      assertTrue(closed.getBoolean(transport), "empty close must close transport");
    }});
    test("private-use close code and reason survive repeated close", new Test() { public void run() throws Exception {
      Object transport = transport(frame(true, 8, join(new byte[] {15, (byte) 160}, bytes("application closed"))), new ByteArrayOutputStream());
      assertEquals(null, call(readMessage, transport));
      String reason = (String) call(getCloseReason, transport);
      assertTrue(reason.indexOf("4000") >= 0 && reason.indexOf("application closed") >= 0, "custom close reason was lost");
      call(close, transport);
      assertEquals(reason, call(getCloseReason, transport));
    }});
    test("EOF without close records abnormal closure", new Test() { public void run() throws Exception {
      Object transport = transport(new byte[0], new ByteArrayOutputStream());
      assertEquals(null, call(readMessage, transport));
      assertTrue(closed.getBoolean(transport), "EOF must close transport");
      assertTrue(((String) call(getCloseReason, transport)).indexOf("1006") >= 0, "EOF must report abnormal close code");
    }});
    test("truncated payload closes transport and records 1006", new Test() { public void run() throws Exception {
      Object transport = transport(new byte[] {(byte) 0x81, 3, 'x'}, new ByteArrayOutputStream());
      expectIOException(readMessage, transport);
      assertTrue(closed.getBoolean(transport), "truncated payload must close transport");
      assertTrue(((String) call(getCloseReason, transport)).indexOf("1006") >= 0, "truncated payload must report abnormal close code");
    }});
    reject("one-byte close payload", frame(true, 8, new byte[] {1}));
    reject("reserved close code", frame(true, 8, new byte[] {3, (byte) 237}));
    reject("invalid close UTF-8", frame(true, 8, new byte[] {3, (byte) 232, (byte) 0xc3, 0x28}));
    reject("invalid text UTF-8", frame(true, 1, new byte[] {(byte) 0xc3, 0x28}));
    reject("unexpected continuation", frame(true, 0, bytes("orphan")));
    reject("second data opcode inside fragments", join(frame(false, 1, bytes("a")), frame(true, 1, bytes("b"))));
    reject("fragmented ping", frame(false, 9, bytes("ping")));
    reject("oversized control payload", frame(true, 9, new byte[126]));
    reject("unknown opcode", frame(true, 3, bytes("data")));
    reject("reserved bit without negotiated extension", new byte[] {(byte) 0xc1, 0});
    reject("masked server frame", maskedFrame(true, 1, bytes("masked")));
    reject("negative 64-bit payload length", new byte[] {(byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0});
    reject("nonminimal 16-bit length", new byte[] {(byte) 0x81, 126, 0, 1, 'x'});
    reject("nonminimal 64-bit length", new byte[] {(byte) 0x81, 127, 0, 0, 0, 0, 0, 0, 0, 1, 'x'});
    reject("message above 4 MiB limit before allocation", new byte[] {(byte) 0x81, 127, 0, 0, 0, 0, 0, 64, 0, 1});
    reject("fragment aggregate above 4 MiB", join(frame(false, 1, new byte[4 * 1024 * 1024]), frame(true, 0, new byte[] {1})));
    reject("truncated frame header", new byte[] {(byte) 0x81});
    reject("truncated extended length", new byte[] {(byte) 0x81, 126, 0});
    reject("truncated frame body", new byte[] {(byte) 0x81, 3, 'x'});

    sendCheck("outgoing short frame", "client");
    sendCheck("outgoing 16-bit frame", repeat('s', 126));
    sendCheck("outgoing 64-bit frame", repeat('t', 65536));
    test("outgoing message size limit", new Test() { public void run() throws Exception {
      Object transport = transport(new byte[0], new ByteArrayOutputStream());
      expectIOException(sendText, transport, repeat('x', 4 * 1024 * 1024 + 1));
    }});
    test("closed transport rejects sending", new Test() { public void run() throws Exception {
      Object transport = transport(new byte[0], new ByteArrayOutputStream());
      call(close, transport);
      expectIOException(sendText, transport, "late");
      call(close, transport);
    }});
    test("simultaneous senders preserve complete frames", new Test() { public void run() throws Exception {
      final ByteArrayOutputStream stream = new ByteArrayOutputStream();
      final Object transport = transport(new byte[0], stream);
      final java.util.Vector<Throwable> errors = new java.util.Vector<Throwable>();
      Thread[] senders = new Thread[6];
      for (int index = 0; index < senders.length; index++) {
        final int sender = index;
        senders[index] = new Thread(new Runnable() { public void run() {
          try {
            for (int message = 0; message < 50; message++) call(sendText, transport, sender + ":" + message + ":" + repeat('z', 9000));
          } catch (Throwable failure) { errors.add(failure); }
        }});
        senders[index].start();
      }
      for (Thread sender : senders) {
        sender.join(5000);
        assertTrue(!sender.isAlive(), "sender thread did not finish");
      }
      assertTrue(errors.isEmpty(), "sender threw an exception: " + errors);
      ByteArrayInputStream frames = new ByteArrayInputStream(stream.toByteArray());
      java.util.HashSet<String> messages = new java.util.HashSet<String>();
      while (frames.available() != 0) {
        Decoded decoded = decode(frames);
        assertTrue(decoded.masked && decoded.fin && decoded.opcode == 1, "invalid concurrent outgoing frame");
        String message = new String(decoded.payload, "UTF-8");
        assertTrue(message.endsWith(repeat('z', 9000)), "concurrent frame payload was corrupted");
        assertTrue(messages.add(message), "duplicate concurrent message");
      }
      assertEquals(Integer.valueOf(300), Integer.valueOf(messages.size()));
    }});
    test("close cancels a writer blocked by backpressure", new Test() { public void run() throws Exception {
      final BlockingOutputStream blocked = new BlockingOutputStream();
      final Object transport = transport(new byte[0], new ByteArrayOutputStream());
      output.set(transport, blocked);
      field("activeSocket").set(transport, new java.net.Socket() {
        public void close() { blocked.release.countDown(); }
      });
      final java.util.Vector<Throwable> errors = new java.util.Vector<Throwable>();
      Thread writer = new Thread(new Runnable() { public void run() {
        try { call(sendText, transport, "blocked"); }
        catch (Throwable failure) { errors.add(failure); }
      }});
      writer.start();
      assertTrue(blocked.entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "writer did not enter blocking output");
      call(close, transport);
      writer.join(2000);
      assertTrue(!writer.isAlive(), "close did not unblock the writer");
      assertTrue(errors.size() == 1 && errors.firstElement() instanceof IOException, "cancelled writer should report IOException");
    }});

    final String key = "dGhlIHNhbXBsZSBub25jZQ==";
    final String good = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n";
    test("valid WebSocket HTTP upgrade", new Test() { public void run() throws Exception {
      call(verifyUpgrade, constructor.newInstance(), good, key);
    }});
    upgradeReject("HTTP error status", good.replace("101 Switching Protocols", "401 Unauthorized"), key);
    upgradeReject("invalid accept hash", good.replace("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "invalid"), key);
    upgradeReject("missing Upgrade header", good.replace("Upgrade: websocket\r\n", ""), key);
    upgradeReject("missing Connection upgrade token", good.replace("keep-alive, Upgrade", "keep-alive"), key);
    upgradeReject("duplicate accept header", good.replace("\r\n\r\n", "\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"), key);
    upgradeReject("unrequested extension", good.replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"), key);
    upgradeReject("unrequested subprotocol", good.replace("\r\n\r\n", "\r\nSec-WebSocket-Protocol: extra\r\n\r\n"), key);
    upgradeReject("NUL in HTTP header value", good.replace("\r\n\r\n", "\r\nX-Test: a" + (char) 0 + "b\r\n\r\n"), key);
    upgradeReject("control character in HTTP header value", good.replace("\r\n\r\n", "\r\nX-Test: a" + (char) 1 + "b\r\n\r\n"), key);
    upgradeReject("DEL character in HTTP header value", good.replace("\r\n\r\n", "\r\nX-Test: a" + (char) 127 + "b\r\n\r\n"), key);
    test("horizontal tab is allowed in HTTP header value", new Test() { public void run() throws Exception {
      call(verifyUpgrade, constructor.newInstance(), good.replace("\r\n\r\n", "\r\nX-Test: a\tb\r\n\r\n"), key);
    }});
    test("header parser preserves following frame bytes", new Test() { public void run() throws Exception {
      ByteArrayInputStream stream = new ByteArrayInputStream(join(bytes(good), frame(true, 1, bytes("first"))));
      assertEquals(good, call(readHttpHeaders, constructor.newInstance(), stream));
      assertEquals(Integer.valueOf(0x81), Integer.valueOf(stream.read()));
    }});
    test("bounded HTTP header length", new Test() { public void run() throws Exception {
      expectIOException(readHttpHeaders, constructor.newInstance(), new ByteArrayInputStream(bytes(repeat('h', 65536))));
    }});

    System.out.println("Transport tests: " + passed + " passed, " + failed + " failed");
    if (failed != 0) throw new AssertionError("Transport validation failed");
  }

  private static void test(String name, Test test) {
    try { test.run(); passed++; System.out.println("PASS " + name); }
    catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.out); }
  }
  private static void reject(final String name, final byte[] bytes) {
    test("reject " + name, new Test() { public void run() throws Exception {
      expectIOException(readMessage, transport(bytes, new ByteArrayOutputStream()));
    }});
  }
  private static void upgradeReject(final String name, final String headers, final String key) {
    test("reject " + name, new Test() { public void run() throws Exception {
      expectIOException(verifyUpgrade, constructor.newInstance(), headers, key);
    }});
  }
  private static void sendCheck(final String name, final String message) {
    test(name, new Test() { public void run() throws Exception {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      call(sendText, transport(new byte[0], stream), message);
      Decoded decoded = decode(stream.toByteArray());
      assertEquals(Integer.valueOf(1), Integer.valueOf(decoded.opcode));
      assertTrue(decoded.masked, "all client messages must be masked");
      assertTrue(decoded.fin, "outgoing frame must be final");
      assertEquals(message, new String(decoded.payload, "UTF-8"));
    }});
  }
  private static Object transport(byte[] incoming, ByteArrayOutputStream outgoing) throws Exception {
    Object transport = constructor.newInstance();
    input.set(transport, new ByteArrayInputStream(incoming));
    output.set(transport, outgoing);
    return transport;
  }
  private static Object read(byte[] incoming) throws Exception {
    return call(readMessage, transport(incoming, new ByteArrayOutputStream()));
  }
  private static Method method(String name, Class<?>... types) throws Exception {
    Method method = transportClass.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method;
  }
  private static Field field(String name) throws Exception {
    Field field = transportClass.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }
  private static Object call(Method method, Object target, Object... args) throws Exception {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new RuntimeException(cause);
    }
  }
  private static void expectIOException(Method method, Object target, Object... args) throws Exception {
    try { call(method, target, args); }
    catch (IOException expected) { return; }
    throw new AssertionError("Expected IOException from " + method.getName());
  }
  private static void assertEquals(Object expected, Object actual) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError("Expected " + expected + ", got " + actual);
    }
  }
  private static void assertTrue(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  private static String repeat(char value, int count) {
    char[] result = new char[count]; Arrays.fill(result, value); return new String(result);
  }
  private static byte[] bytes(String text) throws Exception { return text.getBytes("UTF-8"); }
  private static byte[] join(byte[]... parts) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    for (byte[] part : parts) stream.write(part, 0, part.length);
    return stream.toByteArray();
  }
  private static byte[] frame(boolean fin, int opcode, byte[] payload) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    stream.write((fin ? 0x80 : 0) | opcode);
    if (payload.length < 126) stream.write(payload.length);
    else if (payload.length <= 65535) {
      stream.write(126); stream.write(payload.length >>> 8); stream.write(payload.length);
    } else {
      stream.write(127);
      for (int shift = 56; shift >= 0; shift -= 8) stream.write((int) (((long) payload.length) >>> shift));
    }
    stream.write(payload, 0, payload.length);
    return stream.toByteArray();
  }
  private static byte[] maskedFrame(boolean fin, int opcode, byte[] payload) {
    byte[] mask = new byte[] {1, 2, 3, 4};
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    stream.write((fin ? 0x80 : 0) | opcode); stream.write(0x80 | payload.length);
    stream.write(mask, 0, mask.length);
    for (int index = 0; index < payload.length; index++) stream.write(payload[index] ^ mask[index & 3]);
    return stream.toByteArray();
  }
  private static final class Decoded {
    boolean fin;
    boolean masked;
    int opcode;
    byte[] payload;
  }
  private static final class BlockingOutputStream extends java.io.OutputStream {
    final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    public void write(int value) throws IOException {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
      throw new IOException("socket closed");
    }
  }
  private static Decoded decode(byte[] bytes) throws Exception {
    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    Decoded decoded = decode(stream);
    assertEquals(Integer.valueOf(0), Integer.valueOf(stream.available()));
    return decoded;
  }
  private static Decoded decode(ByteArrayInputStream stream) throws Exception {
    int first = stream.read(), second = stream.read();
    if (first < 0 || second < 0) throw new AssertionError("missing outgoing frame");
    Decoded decoded = new Decoded();
    decoded.fin = (first & 128) != 0; decoded.opcode = first & 15; decoded.masked = (second & 128) != 0;
    long length = second & 127;
    if (length == 126) length = (stream.read() << 8) | stream.read();
    else if (length == 127) {
      length = 0;
      for (int index = 0; index < 8; index++) length = (length << 8) | stream.read();
    }
    if (length < 0 || length > Integer.MAX_VALUE) throw new AssertionError("invalid outgoing length");
    byte[] mask = new byte[4];
    if (decoded.masked && stream.read(mask) != 4) throw new AssertionError("missing mask");
    decoded.payload = new byte[(int) length];
    if (decoded.payload.length > 0 && stream.read(decoded.payload) != decoded.payload.length) throw new AssertionError("truncated outgoing data");
    if (decoded.masked) for (int index = 0; index < decoded.payload.length; index++) decoded.payload[index] ^= mask[index & 3];
    return decoded;
  }
}
