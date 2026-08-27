package io.akka.glance.net;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The clients the widgets fetch through.
 *
 * <p>Three of them, the same three the original keeps: an ordinary one, one that accepts an
 * untrusted certificate, and one per configured proxy. The five-second timeout is the
 * original's {@code defaultClientTimeout}.
 */
public final class HttpClients {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private static final HttpClient DEFAULT =
      HttpClient.newBuilder()
          .connectTimeout(DEFAULT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .proxy(ProxySelector.getDefault())
          .build();

  private static final HttpClient INSECURE = insecureClient();

  private HttpClients() {}

  public static HttpClient standard() {
    return DEFAULT;
  }

  public static HttpClient insecure() {
    return INSECURE;
  }

  public static HttpClient through(URI proxy, Duration timeout, boolean allowInsecure) {
    var builder =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(
                ProxySelector.of(
                    new InetSocketAddress(
                        proxy.getHost(), proxy.getPort() < 0 ? defaultPort(proxy) : proxy.getPort())));
    if (allowInsecure) {
      builder.sslContext(trustEverything());
    }
    return builder.build();
  }

  private static int defaultPort(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static HttpClient insecureClient() {
    return HttpClient.newBuilder()
        .connectTimeout(DEFAULT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .sslContext(trustEverything())
        .build();
  }

  /**
   * Accepts any certificate, which is what {@code allow-insecure} asks for: a self-signed
   * certificate on a service inside the same house is the case it exists for.
   */
  private static SSLContext trustEverything() {
    try {
      var context = SSLContext.getInstance("TLS");
      context.init(
          null,
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          },
          new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("building an insecure TLS context", e);
    }
  }

  /**
   * The browser user agent the original sends where a site refuses its own. The version moves
   * about one request in two thousand, which is what the original does to look ordinary.
   */
  private static final AtomicInteger USER_AGENT_VERSION = new AtomicInteger();

  private static final java.util.Random RANDOM = new java.util.Random();

  public static String browserUserAgent() {
    if (RANDOM.nextInt(2000) == 0) {
      USER_AGENT_VERSION.set(RANDOM.nextInt(5));
    }
    int version = 148 + USER_AGENT_VERSION.get();
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:"
        + version
        + ".0) Gecko/20100101 Firefox/"
        + version
        + ".0";
  }
}
