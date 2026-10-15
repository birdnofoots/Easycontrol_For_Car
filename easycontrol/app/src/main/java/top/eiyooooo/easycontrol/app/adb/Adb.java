package top.eiyooooo.easycontrol.app.adb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

  // 多地址：并行竞速，选取"TCP 连得上 且 ADB 认证通过"的第一个地址
  public Adb(String uuid, List<String> addresses, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    Pair<TcpChannel, Integer> r = tryConnectAny(addresses, false, keyPair);
    channel = r.first;
    MAX_DATA = r.second;
    finishConnect();
    startServerThread.start();
  }

  public Adb(List<String> addresses, AdbKeyPair keyPair) throws Exception {
    this.uuid = null;
    Pair<TcpChannel, Integer> r = tryConnectAny(addresses, true, keyPair);
    channel = r.first;
    MAX_DATA = r.second;
    finishConnect();
  }

  public Adb(String uuid, UsbDevice usbDevice, AdbKeyPair keyPair) throws Exception {
    this.uuid = uuid;
    channel = new UsbChannel(usbDevice);
    connect(keyPair);
    startServerThread.start();
  }

  // 并行竞速多个地址，返回最早"TCP 连接成功 且 ADB 认证通过"的 <channel, maxData>。
  //
  // 两条真机踩出来的关键经验：
  //  1) 必须"先完成者优先"，不能按列表顺序等待。
  //     列表第 1 个地址不可达时，TCP 失败可能耗近 4s（No route to host 也要等内核超时），
  //     会把后面本可秒连的地址一起拖过 Client 的 5s 连接看门狗。
  //  2) **TCP 连得上 != ADB 可用**。
  //     某些地址能完成三次握手，但 ADB 握手会被丢弃/重置（等价于 adb 的 "device offline"），
  //     例如被容器/VPN 接管网段、MTU 黑洞、单向 NAT 等场景。
  //     因此认证必须放进竞速里：只有认证也通过的地址才算可用；
  //     否则会选中一个"半通"地址，导致整体连不上、又不会回落到其它地址。
  private static Pair<TcpChannel, Integer> tryConnectAny(List<String> addresses, boolean test, AdbKeyPair keyPair) throws Exception {
    if (addresses == null || addresses.isEmpty()) {
      throw new IOException("no address available");
    }
    if (addresses.size() == 1) {
      Pair<String, Integer> p = PublicTools.getIpAndPort(addresses.get(0));
      TcpChannel ch = new TcpChannel(p.first, p.second, test);
      try {
        return new Pair<>(ch, authChannel(ch, keyPair));
      } catch (Exception e) {
        ch.close();
        throw e;
      }
    }
    final ExecutorService exec = Executors.newCachedThreadPool();
    final CompletionService<Pair<TcpChannel, Integer>> completion = new ExecutorCompletionService<>(exec);
    // 唯一登记的"已可用"连接：只有第一个通过认证的任务能登记（CAS 保证唯一）
    final AtomicReference<Pair<TcpChannel, Integer>> produced = new AtomicReference<>();
    final AtomicBoolean abandoned = new AtomicBoolean(false);
    final long overallTimeoutMs = 4500;
    final long deadline = System.nanoTime() + overallTimeoutMs * 1_000_000L;

    for (String addr : addresses) {
      final String a = addr;
      completion.submit(() -> {
        if (abandoned.get()) return null;
        TcpChannel ch = null;
        try {
          Pair<String, Integer> p = PublicTools.getIpAndPort(a);
          ch = new TcpChannel(p.first, p.second, test);
          final TcpChannel channel = ch;
          Pair<TcpChannel, Integer> ok = new Pair<>(ch, authChannel(ch, keyPair));
          if (!produced.compareAndSet(null, ok)) {
            channel.close(); // 已经有更早可用的连接了
            return null;
          }
          // 登记后若发现主流程已放弃，则由本线程负责关闭，避免泄漏
          if (abandoned.get() && produced.compareAndSet(ok, null)) channel.close();
          return ok;
        } catch (Exception ignored) {
          if (ch != null) ch.close(); // TCP 通了但认证失败：必须释放
          return null;
        }
      });
    }

    Pair<TcpChannel, Integer> winner = null;
    try {
      for (int i = 0; i < addresses.size(); i++) {
        long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) break;
        Future<Pair<TcpChannel, Integer>> f;
        try {
          f = completion.poll(remainingMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // 保留中断标志，不吞掉
          break;
        }
        if (f == null) break; // 已到全局 deadline
        try {
          Pair<TcpChannel, Integer> r = f.get();
          if (r != null) { winner = r; break; } // 最早"TCP + ADB"都通的地址
        } catch (CancellationException | ExecutionException ignored) {
          // 该地址失败：继续等下一个完成者
        }
      }
    } finally {
      abandoned.set(true); // 先标记放弃，此后新建连接的线程会自行 close
      exec.shutdownNow();
      // 兜底：若在放弃瞬间刚好有连接完成认证，要么采纳、要么关闭，二者必居其一
      Pair<TcpChannel, Integer> late = produced.getAndSet(null);
      if (late != null && late != winner) {
        if (winner == null) winner = late; // 临界时刻连上的，直接采用
        else late.first.close();
      }
    }
    if (winner != null) return winner;
    throw new IOException("all addresses unusable (tcp+adb): " + addresses);
  }

  // 在通道上完成 ADB 握手认证，返回 CNXN 协商出的 maxData。失败抛异常（由调用方负责关闭通道）
  private static int authChannel(AdbChannel ch, AdbKeyPair keyPair) throws Exception {
    // 连接ADB并认证
    ch.write(AdbProtocol.generateConnect());
    AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(ch);
    if (message.command == AdbProtocol.CMD_AUTH) {
      ch.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)));
      message = AdbProtocol.AdbMessage.parseAdbMessage(ch);
      if (message.command == AdbProtocol.CMD_AUTH) {
        ch.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes));
        message = AdbProtocol.AdbMessage.parseAdbMessage(ch);
      }
    }
    if (message.command != AdbProtocol.CMD_CNXN) throw new Exception("ADB connect error");
    return message.arg1;
  }

  private void connect(AdbKeyPair keyPair) throws Exception {
    try {
      MAX_DATA = authChannel(channel, keyPair);
    } catch (Exception e) {
      // 认证失败：先释放通道再抛出（TCP 通但 ADB 不可用时不能留下半开连接）
      channel.close();
      throw e;
    }
    finishConnect();
  }

  // 认证通过后的收尾：测试模式（uuid == null）只做连通性验证随后关闭；否则启动收发线程
  private void finishConnect() {
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
