package io.akka.glance.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a post's engagement falls with age, from the original's own {@code widget-shared_test.go}.
 *
 * <p>Every post is given the same comment count and score so that each starts at exactly 1.0.
 * That leaves age as the only thing separating them, which is what these assert about.
 */
class ForumPostTest {

  private static ForumPost aged(Instant now, long hours) {
    var post = new ForumPost();
    post.CommentCount = 100;
    post.Score = 100;
    post.TimePosted = now.minus(Duration.ofHours(hours));
    return post;
  }

  @Test
  void engagementFallsGraduallyAndIsFloored() {
    var now = Instant.now();
    var posts = List.of(aged(now, 1), aged(now, 8), aged(now, 50));
    ForumPost.calculateEngagement(posts, now);

    double fresh = posts.get(0).Engagement;
    double recent = posts.get(1).Engagement;
    double old = posts.get(2).Engagement;

    assertEquals(1.0, fresh, 1e-6, "a post younger than the threshold keeps all of it");
    assertTrue(recent > 0.9, "an hour past the threshold should cost a few percent, not all of it");
    assertTrue(old >= 0, "however old a post is, its engagement never goes below zero");
    assertEquals(0.1, old, 1e-6, "and it is floored at one minus the maximum depreciation");
    assertTrue(fresh >= recent && recent > old, "engagement falls with age");
  }
}
