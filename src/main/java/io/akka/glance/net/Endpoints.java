package io.akka.glance.net;

/**
 * The addresses of the services the widgets read from.
 *
 * <p>The original writes each of these inline. They are gathered here so that a test can
 * point one at a server of its own and drive the real fetching code; the defaults are the
 * original's own addresses, so nothing about a running system depends on this.
 */
public final class Endpoints {

  public static String hackerNews = property("hacker-news", "https://hacker-news.firebaseio.com/v0/");
  public static String lobsters = property("lobsters", "https://lobste.rs/");
  public static String reddit = property("reddit", "https://www.reddit.com");
  public static String twitchGql = property("twitch-gql", "https://gql.twitch.tv/gql");
  public static String twitchWebsite = property("twitch-website", "https://www.twitch.tv");
  public static String github = property("github", "https://api.github.com");
  public static String dockerHub = property("docker-hub", "https://hub.docker.com");
  public static String gitlab = property("gitlab", "https://gitlab.com");
  public static String codeberg = property("codeberg", "https://codeberg.org");
  public static String openMeteo = property("open-meteo", "https://api.open-meteo.com");
  public static String openMeteoGeocoding =
      property("open-meteo-geocoding", "https://geocoding-api.open-meteo.com");
  public static String yahooFinance = property("yahoo-finance", "https://query1.finance.yahoo.com");
  public static String youtube = property("youtube", "https://www.youtube.com");

  /** The client id Twitch's own web player sends, which its public API expects. */
  public static final String TWITCH_GQL_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";

  private Endpoints() {}

  /**
   * The address for one service: a system property, then an environment variable, then the
   * original's own. Both spellings exist because a test sets a property in its own process
   * and a running service is given an environment.
   */
  private static String property(String name, String fallback) {
    String property = System.getProperty("glance.endpoint." + name);
    if (property != null && !property.isEmpty()) {
      return property;
    }
    String environment =
        System.getenv("GLANCE_ENDPOINT_" + name.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    return environment == null || environment.isEmpty() ? fallback : environment;
  }
}
