package io.akka.glance.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One HTTP request over a Unix domain socket.
 *
 * <p>Docker's daemon listens on a socket rather than a port, and the JDK's HTTP client will
 * not open one. This writes the request line and headers itself and reads the response,
 * which is all the one call the docker widget makes needs — a GET, no redirects, no
 * continuation.
 */
public final class UnixSocketHttp {

  private UnixSocketHttp() {}

  /** What came back: the status line's code, and the body. */
  public record Response(int status, String statusLine, String body) {}

  public static Response get(String socketPath, String path, Duration timeout) throws IOException {
    var address = UnixDomainSocketAddress.of(socketPath);
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(address);
      String request =
          "GET "
              + path
              + " HTTP/1.1\r\n"
              + "Host: docker\r\n"
              + "User-Agent: "
              + Requests.USER_AGENT
              + "\r\n"
              + "Accept: application/json\r\n"
              + "Connection: close\r\n\r\n";
      channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
      var collected = new ByteArrayOutputStream();
      var buffer = ByteBuffer.allocate(16 * 1024);
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        buffer.clear();
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        collected.write(buffer.array(), 0, read);
        if (System.nanoTime() > deadline) {
          throw new IOException("context deadline exceeded");
        }
      }
      return parse(collected.toString(StandardCharsets.UTF_8));
    }
  }

  private static Response parse(String raw) throws IOException {
    int separator = raw.indexOf("\r\n\r\n");
    if (separator < 0) {
      throw new IOException("malformed response");
    }
    String head = raw.substring(0, separator);
    String body = raw.substring(separator + 4);
    var lines = head.split("\r\n");
    var statusParts = lines[0].split(" ", 3);
    if (statusParts.length < 2) {
      throw new IOException("malformed status line");
    }
    int status = Integer.parseInt(statusParts[1]);
    String statusLine = lines[0].substring(lines[0].indexOf(' ') + 1);
    boolean chunked = false;
    for (int i = 1; i < lines.length; i++) {
      var line = lines[i];
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      if (line.substring(0, colon).trim().equalsIgnoreCase("Transfer-Encoding")
          && line.substring(colon + 1).trim().equalsIgnoreCase("chunked")) {
        chunked = true;
      }
    }
    return new Response(status, statusLine, chunked ? dechunk(body) : body);
  }

  private static String dechunk(String body) throws IOException {
    var out = new StringBuilder();
    int at = 0;
    while (at < body.length()) {
      int lineEnd = body.indexOf("\r\n", at);
      if (lineEnd < 0) {
        break;
      }
      String sizeLine = body.substring(at, lineEnd).trim();
      int semicolon = sizeLine.indexOf(';');
      if (semicolon >= 0) {
        sizeLine = sizeLine.substring(0, semicolon);
      }
      int size;
      try {
        size = Integer.parseInt(sizeLine, 16);
      } catch (NumberFormatException e) {
        throw new IOException("malformed chunk size");
      }
      if (size == 0) {
        break;
      }
      int start = lineEnd + 2;
      out.append(body, start, Math.min(start + size, body.length()));
      at = start + size + 2;
    }
    return out.toString();
  }
}
