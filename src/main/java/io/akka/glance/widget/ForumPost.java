package io.akka.glance.widget;

import io.akka.glance.util.GoTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A post on a link-sharing site, as the three widgets that show one all describe it.
 *
 * <p>{@code Engagement} is a rank rather than a measurement: the comment count and the score
 * each against the average of the set, halved, and then reduced for age.
 */
public final class ForumPost {

  public String Title = "";
  public String DiscussionUrl = "";
  public String TargetUrl = "";
  public String TargetUrlDomain = "";
  public String ThumbnailUrl = "";
  public int CommentCount;
  public int Score;
  public double Engagement;
  public Instant TimePosted = GoTime.ZERO;
  public List<String> Tags = new ArrayList<>();
  public boolean IsCrosspost;

  /** A post older than this many hours starts losing engagement. */
  private static final int DEPRECIATE_AFTER_HOURS = 7;

  private static final double MAX_DEPRECIATION = 0.9;
  private static final int MAX_DEPRECIATION_AFTER_HOURS = 24;

  public static void calculateEngagement(List<ForumPost> posts, Instant now) {
    long totalComments = 0;
    long totalScore = 0;
    for (var post : posts) {
      totalComments += post.CommentCount;
      totalScore += post.Score;
    }
    double count = posts.size();
    double averageComments = totalComments / count;
    double averageScore = totalScore / count;
    for (var post : posts) {
      post.Engagement = (post.CommentCount / averageComments + post.Score / averageScore) / 2;
      double elapsedHours = Duration.between(post.TimePosted, now).toNanos() / 3_600_000_000_000.0;
      if (elapsedHours < DEPRECIATE_AFTER_HOURS) {
        continue;
      }
      post.Engagement *=
          1.0
              - (Math.min(elapsedHours - DEPRECIATE_AFTER_HOURS, MAX_DEPRECIATION_AFTER_HOURS)
                      / MAX_DEPRECIATION_AFTER_HOURS)
                  * MAX_DEPRECIATION;
    }
  }

  public static void sortByEngagement(List<ForumPost> posts) {
    posts.sort(Comparator.comparingDouble((ForumPost post) -> post.Engagement).reversed());
  }
}
