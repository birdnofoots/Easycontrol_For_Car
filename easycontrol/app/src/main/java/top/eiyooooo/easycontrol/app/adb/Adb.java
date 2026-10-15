package top.eiyooooo.easycontrol.app.adb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;

import android.util.Pair;
import top.eiyooooo.easycontrol.app.BuildConfig;
import top.eiyooooo.easycontrol.app.R;
import top.eiyooooo.easycontrol.app.buffer.Buffer;
import top.eiyooooo.easycontrol.app.buffer.BufferStream;
import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.helper.L;
import top.eiyooooo.easycontrol.app.helper.PublicTools;

public class Adb {
  // 多线程读写（连接检查线程/Client 线程/UI 线程），必须用并发安全容器
  public static final ConcurrentHashMap<String, Adb> adbMap = new ConcurrentHashMap<>();

  private final AdbChannel channel;
  private int localIdPool = 1;
  private int MAX_DATA = AdbProtocol.CONNECT_MAXDATA;
  private final ConcurrentHashMap<Integer, BufferStream> connectionStreams = new ConcurrentHashMap<>(10);
  private final ConcurrentHashMap<Integer, BufferStream> openStreams = new ConcurrentHashMap<>(5);
  private final Buffer sendBuffer = new Buffer();

  private final Thread handleInThread = new Thread(this::handleIn);
  private final Thread handleOutThread = new Thread(this::handleOut);

  private final String uuid;
  private static final String serverName = "/data/local/tmp/easycontrol_for_car_server_" + BuildConfig.VERSION_CODE + ".jar";
  public Thread startServerThread = new Thread(this::startServer);
  public BufferStream serverShell;

  // ========== 多地址连接支持 ==========
  // 单地址：保持原有构造函数
  public Adb(String uuid, String address, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    Pair<String, Integer> addressPair = PublicTools.getIpAndPort(address);
    channel = new TcpChannel(addressPair.first, addressPair.second, false);
    connect(keyPair);
    startServerThread.start();
  }

  public Adb(String address, AdbKeyPair keyPair) throws Exception {
    this.uuid = null;
    Pair<String, Integer> addressPair = PublicTools.getIpAndPort(address);
    channel = new TcpChannel(addressPair.first, addressPair.second, true);
    connect(keyPair);
  }

  // 多地址：按顺序逐个尝试（兼容旧的"任一可用即可"语义）
  public Adb(String uuid, List<String> addresses, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    channel = tryConnectAny(addresses, false);
    connect(keyPair);
    startServerThread.start();
  }

  public Adb(List<String> addresses, AdbKeyPair keyPair) throws Exception {
    this.uuid = null;
    channel = tryConnectAny(addresses, true);
    connect(keyPair);
  }

  public Adb(String uuid, UsbDevice usbDevice, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    channel = new UsbChannel(usbDevice);
    connect(keyPair);
    startServerThread.start();
  }

  // 并行尝试多个地址，返回第一个成功建立的 TcpChannel，全部失败则抛异常。
  // - 单地址走原路径，避免线程开销
  // - 多地址并行尝试，整体延迟 = max(单个超时)，而非 sum
  // - 全局 deadline 6s（略大于 TcpChannel 自身 5s），所有 future 共享同一截止时间
  // - 放弃等待后仍可能有线程建连成功，必须显式关闭，否则 socket 泄漏
  private static TcpChannel tryConnectAny(List<String> addresses, boolean test) throws Exception {
    if (addresses == null || addresses.isEmpty()) {
      throw new IOException("no address available");
    }
    if (addresses.size() == 1) {
      Pair<String, Integer> p = PublicTools.getIpAndPort(addresses.get(0));
      return new TcpChannel(p.first, p.second, test);
    }
    final ExecutorService exec = Executors.newCachedThreadPool();
    final List<Future<TcpChannel>> futures = new ArrayList<>();
    final AtomicBoolean done = new AtomicBoolean(false);
    final long overallTimeoutMs = 6000;
    final long deadline = System.nanoTime() + overallTimeoutMs * 1_000_000L;
    for (String addr : addresses) {
      final String a = addr;
      futures.add(exec.submit(() -> {
        if (done.get()) return null;
        try {
          Pair<String, Integer> p = PublicTools.getIpAndPort(a);
          TcpChannel ch = new TcpChannel(p.first, p.second, test);
          if (done.compareAndSet(false, true)) {
            return ch; // 第一个建立成功的连接
          }
          ch.close(); // 别人抢先了，或主流程已放弃 -> 必须关闭
          return null;
        } catch (Exception ignored) {
          return null;
        }
      }));
    }
    TcpChannel winner = null;
    try {
      for (Future<TcpChannel> f : futures) {
        long remainingMs = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
        try {
          TcpChannel r = f.get(remainingMs, TimeUnit.MILLISECONDS);
          if (r != null) { winner = r; break; }
        } catch (TimeoutException e) {
          break; // 已到全局 deadline，不再等待后续 future
        } catch (ExecutionException ignored) {
          // 单个地址失败：尝试下一个
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // 保留中断标志，不吞掉
          break;
        }
      }
    } finally {
      done.set(true); // 关键：先标记放弃，此后新建连接的线程会自行 close
      for (Future<TcpChannel> f : futures) f.cancel(true);
      exec.shutdownNow();
      // 回收"取消时已在途、取消不生效"的连接：扫描已完成但未被采纳的 channel
      for (Future<TcpChannel> f : futures) {
        if (f.isDone() && !f.isCancelled()) {
          try {
            TcpChannel extra = f.get(); // isDone() 已保证立即返回，无需 getNow
            if (extra != null && extra != winner) extra.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
    if (winner != null) return winner;
    throw new IOException("all addresses unreachable: " + addresses);
  }

  private void connect(AdbKeyPair keyPair) throws Exception {
    // 连接ADB并认证
    channel.write(AdbProtocol.generateConnect());
    AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
    if (message.command == AdbProtocol.CMD_AUTH) {
      channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)));
      message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      if (message.command == AdbProtocol.CMD_AUTH) {
        channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes));
        message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      }
    }
    if (message.command != AdbProtocol.CMD_CNXN) {
      channel.close();
      throw new Exception("ADB connect error");
    }
    MAX_DATA = message.arg1;
    if (uuid == null) {
      channel.close();
      return;
    }
    // 启动后台进程
    handleInThread.setPriority(Thread.MAX_PRIORITY);
    handleInThread.start();
    handleOutThread.start();
  }

  public final void startServer() {
    try {
      if (BuildConfig.ENABLE_DEBUG_FEATURE || !runAdbCmd("ls /data/local/tmp/easycontrol_*").contains(serverName)) {
        runAdbCmd("rm /data/local/tmp/easycontrol_* ");
        pushFile(AppData.main.getResources().openRawResource(R.raw.easycontrol_server), serverName);
      }
      if (serverShell != null) serverShell.close();
      String cmd = "CLASSPATH=" + serverName + " app_process / top.eiyooooo.easycontrol.server.Server\n";
      serverShell = getShell();
      serverShell.write(ByteBuffer.wrap(cmd.getBytes()));
      waitingData(0);
    } catch (Exception e) {
      L.log(uuid, e);
      PublicTools.logToast(AppData.main.getString(R.string.log_notify));
    }
  }

  public static String getStringResponseFromServer(Device device, String request, String... args) throws Exception {
    Adb adb = getAdb(device);
    return adb.getStringResponse(request, args);
  }

  private static Adb getAdb(Device device) throws Exception {
    String uuid = device.uuid;
    Adb adb = adbMap.get(uuid);
    if (adb == null) throw new Exception("adb not start");
    if (!adb.startServerThread.isAlive() && (adb.serverShell == null || adb.serverShell.isClosed())) {
      adb.startServerThread = new Thread(adb::startServer);
      adb.startServerThread.start();
    }
    if (adb.startServerThread.isAlive())
      adb.startServerThread.join();
    return adb;
  }

  private String getStringResponse(String request, String... args) throws Exception {
    return new String(getResponse(request, args), StandardCharsets.UTF_8);
  }

  private byte[] getResponse(String request, String[] args) throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append("/").append(request).append("?");
    for (String arg : args) {
      sb.append(arg).append("&");
    }
    sb.deleteCharAt(sb.length() - 1).append("\n");
    String requestCmd = sb.toString();

    serverShell.readAllBytes();
    serverShell.write(ByteBuffer.wrap(requestCmd.getBytes()));
    serverShell.readByteArray(requestCmd.length() + 1);
    waitingData(8);
    int len1 = serverShell.readInt();
    int len2 = serverShell.readInt();
    if (len1 == len2) return serverShell.readByteArray(len1).array();
    else throw new Exception("bad data format");
  }

  ArrayList<Integer> dataReceivingList = new ArrayList<>();
  private void waitingData(int byteNum) throws InterruptedException {
    dataReceivingList.clear();
    while (true) {
      int newSize = serverShell.getSize();
      if (newSize == 0) continue;
      if (byteNum > 0 && newSize > byteNum)
        break;
      dataReceivingList.add(newSize);
      if (dataReceivingList.size() > 1) {
        int oldSize = dataReceivingList.get(dataReceivingList.size() - 2);
        if (oldSize == newSize) break;
      }
      Thread.sleep(400);
    }
  }

  private BufferStream open(String destination, boolean canMultipleSend) throws InterruptedException {
    int localId = localIdPool++ * (canMultipleSend ? 1 : -1);
    sendBuffer.write(AdbProtocol.generateOpen(localId, destination));
    BufferStream bufferStream;
    do {
      synchronized (this) {
        wait();
      }
      bufferStream = openStreams.get(localId);
    } while (bufferStream == null);
    openStreams.remove(localId);
    return bufferStream;
  }

  public final String restartOnTcpip(int port) throws InterruptedException {
    closing = true;
    BufferStream bufferStream = open("tcpip:" + port, false);
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
    return new String(bufferStream.readByteArrayBeforeClose().array());
  }

  public final void pushFile(InputStream file, String remotePath) throws Exception {
    // 打开链接
    BufferStream bufferStream = open("sync:", false);
    // 发送信令，建立push通道
    String sendString = remotePath + ",33206";
    byte[] bytes = sendString.getBytes();
    bufferStream.write(AdbProtocol.generateSyncHeader("SEND", sendString.length()));
    bufferStream.write(ByteBuffer.wrap(bytes));
    // 发送文件
    byte[] byteArray = new byte[10240 - 8];
    int len = file.read(byteArray, 0, byteArray.length);
    do {
      bufferStream.write(AdbProtocol.generateSyncHeader("DATA", len));
      bufferStream.write(ByteBuffer.wrap(byteArray, 0, len));
      len = file.read(byteArray, 0, byteArray.length);
    } while (len > 0);
    file.close();
    // 传输完成，为了方便，文件日期定为2024.1.1 0:0
    bufferStream.write(AdbProtocol.generateSyncHeader("DONE", 1704038400));
    bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
  }

  public static Bitmap getRemoteIconByDevice(Device device, String packageName) throws Exception {
    Adb adb = getAdb(device);
    return adb.getRemoteIcon(packageName);
  }

  public final Bitmap getRemoteIcon(String packageName) throws Exception {
    String path = getStringResponse("getIcon", "package=" + packageName);
    if (!path.endsWith(".png")) throw new Exception("get icon fail");
    BufferStream bufferStream = open("sync:", false);
    byte[] bytes = path.getBytes();
    bufferStream.write(AdbProtocol.generateSyncHeader("RECV", bytes.length));
    bufferStream.write(ByteBuffer.wrap(bytes));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    String check1 = new String(bufferStream.readByteArray(4).array());
    if ("FAIL".equals(check1)) throw new Exception("get icon fail");
    int len1 = bufferStream.readByteArray(4).getInt() * 256;
    int size = bufferStream.getSize();
    if (size > len1) size = len1;
    int position = 0;
    do {
      byteArrayOutputStream.write(bufferStream.readByteArray(size).array(), 0, size);
      position += size;
      if (position == len1) {
        String check2 = new String(bufferStream.readByteArray(4).array());
        if (!"FAIL".equals(check2)) {
          len1 = bufferStream.readByteArray(4).getInt() * 256;
          if (len1 != 65536) {
            int len2 = bufferStream.getSize() - 8;
            byteArrayOutputStream.write(bufferStream.readByteArray(len2).array(), 0, len2);
            bufferStream.readByteArray(4);
          }
          position = 0;
        }
      } else {
        if (bufferStream.getSize() == 0) Thread.sleep(120);
        int len3 = bufferStream.getSize();
        if (position + len3 > len1) len3 = len1 - position;
        size = len3;
      }
    } while (bufferStream.getSize() > 0);
    bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
    byteArrayOutputStream.flush();
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
    runAdbCmd("rm " + path);
    return BitmapFactory.decodeByteArray(byteArrayOutputStream.toByteArray(), 0, byteArrayOutputStream.size());
  }

  public final String runAdbCmd(String cmd) throws InterruptedException {
    BufferStream bufferStream = open("shell:" + cmd, true);
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
    return new String(bufferStream.readByteArrayBeforeClose().array());
  }

  public BufferStream getShell() throws InterruptedException {
    return open("shell:", true);
  }

  public BufferStream tcpForward(int port) throws IOException, InterruptedException {
    BufferStream bufferStream = open("tcp:" + port, true);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  public BufferStream localSocketForward(String socketName) throws IOException, InterruptedException {
    BufferStream bufferStream = open("localabstract:" + socketName, true);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  private void handleIn() {
    try {
      while (!Thread.interrupted()) {
        AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
        BufferStream bufferStream = connectionStreams.get(message.arg1);
        boolean isNeedNotify = bufferStream == null;
        // 新连接
        if (isNeedNotify) bufferStream = createNewStream(message.arg1, message.arg0, message.arg1 > 0);
        switch (message.command) {
          case AdbProtocol.CMD_OKAY:
            bufferStream.setCanWrite(true);
            break;
          case AdbProtocol.CMD_WRTE:
            bufferStream.pushSource(message.payload);
            sendBuffer.write(AdbProtocol.generateOkay(message.arg1, message.arg0));
            break;
          case AdbProtocol.CMD_CLSE:
            bufferStream.close();
            isNeedNotify = true;
            break;
        }
        if (isNeedNotify) {
          synchronized (this) {
            notifyAll();
          }
        }
      }
    } catch (Exception e) {
      if (!closing) {
        L.log(uuid, e);
        PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      }
      close();
    }
  }

  private void handleOut() {
    try {
      while (!Thread.interrupted()) {
        channel.write(sendBuffer.readNext());
        if (!sendBuffer.isEmpty()) channel.write(sendBuffer.read(sendBuffer.getSize()));
        channel.flush();
      }
    } catch (Exception e) {
      if (!closing) {
        L.log(uuid, e);
        PublicTools.logToast(AppData.main.getString(R.string.log_notify));
      }
      close();
    }
  }

  private BufferStream createNewStream(int localId, int remoteId, boolean canMultipleSend) throws Exception {
    return new BufferStream(false, canMultipleSend, new BufferStream.UnderlySocketFunction() {
      @Override
      public void connect(BufferStream bufferStream) {
        connectionStreams.put(localId, bufferStream);
        openStreams.put(localId, bufferStream);
      }

      @Override
      public void write(BufferStream bufferStream, ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
          byte[] byteArray = new byte[Math.min(MAX_DATA - 128, buffer.remaining())];
          buffer.get(byteArray);
          sendBuffer.write(AdbProtocol.generateWrite(localId, remoteId, byteArray));
        }
      }

      @Override
      public void flush(BufferStream bufferStream) {
        sendBuffer.write(AdbProtocol.generateOkay(localId, remoteId));
      }

      @Override
      public void close(BufferStream bufferStream) {
        connectionStreams.remove(localId);
        sendBuffer.write(AdbProtocol.generateClose(localId, remoteId));
      }
    });
  }

  boolean closing = false;

  public void close() {
    adbMap.remove(uuid);
    closing = true;
    for (Object bufferStream : connectionStreams.values().toArray()) ((BufferStream) bufferStream).close();
    handleInThread.interrupt();
    handleOutThread.interrupt();
    channel.close();
  }

}
