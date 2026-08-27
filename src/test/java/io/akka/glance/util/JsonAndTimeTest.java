package io.akka.glance.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Reading a JSON document by path, and reading and writing a time by Go's layouts. */
class JsonAndTimeTest {

  private static final String DOCUMENT =
      """
      {
        "total": 13,
        "ratio": 1.5,
        "on": true,
        "name": "a value",
        "nested": {"deep": {"leaf": "found"}},
        "items": [
          {"name": "first", "count": 3},
          {"name": "second", "count": 9}
        ]
      }
      """;

  @Test
  void aPathReadsWhatItNames() {
    var json = JsonResult.parse(DOCUMENT);
    assertEquals(13, json.Int("total"));
    assertEquals(1.5, json.Float("ratio"));
    assertTrue(json.Bool("on"));
    assertEquals("a value", json.String("name"));
    assertEquals("found", json.String("nested.deep.leaf"));
    assertEquals("second", json.String("items.1.name"));
  }

  @Test
  void aPathThatNamesNothingExistsNotAndReadsAsEmpty() {
    var json = JsonResult.parse(DOCUMENT);
    assertFalse(json.Exists("nope"));
    assertFalse(json.Exists("nested.nope.leaf"));
    assertEquals("", json.String("nope"));
    assertEquals(0, json.Int("nope"));
  }

  @Test
  void aHashCountsAnArrayAndTakesAFieldFromEachOfIt() {
    var json = JsonResult.parse(DOCUMENT);
    assertEquals(2, json.Int("items.#"));
    assertEquals(2, json.Array("items.#.name").size());
    assertEquals("first", json.Array("items.#.name").getFirst().String(""));
  }

  @Test
  void anArrayIsWalkedInOrder() {
    var items = JsonResult.parse(DOCUMENT).Array("items");
    assertEquals(2, items.size());
    assertEquals(3, items.get(0).Int("count"));
    assertEquals(9, items.get(1).Int("count"));
  }

  @Test
  void aNumberWrittenAsTextStillReadsAsANumber() {
    var json = JsonResult.parse("{\"n\": \"42\"}");
    assertEquals(42, json.Int("n"));
    assertEquals(42.0, json.Float("n"));
  }

  @Test
  void aGoLayoutBecomesTheSameShapeInJava() {
    assertEquals("uuuu-MM-dd'T'HH:mm:ssXXX", GoLayout.toJavaPattern("2006-01-02T15:04:05Z07:00"));
    assertEquals("uuuu-MM-dd", GoLayout.toJavaPattern("2006-01-02"));
    assertEquals("EEE, dd MMM uuuu HH:mm:ss xx", GoLayout.toJavaPattern("Mon, 02 Jan 2006 15:04:05 -0700"));
  }

  @Test
  void anInstantIsReadAndWrittenByItsLayout() {
    var parsed = GoLayout.parse("rfc3339", "2024-03-04T09:00:00Z", ZoneOffset.UTC);
    assertEquals(Instant.parse("2024-03-04T09:00:00Z"), parsed);
    assertEquals("2024-03-04", GoLayout.format("dateonly", parsed, ZoneOffset.UTC));
    assertEquals("1709542800", GoLayout.format("unix", parsed, ZoneOffset.UTC));
    assertEquals(parsed, GoLayout.parse("unix", "1709542800", ZoneOffset.UTC));
  }

  @Test
  void anUnreadableInstantIsTheEpoch() {
    assertEquals(Instant.EPOCH, GoLayout.parse("rfc3339", "not a time", ZoneOffset.UTC));
    assertEquals(Instant.EPOCH, GoLayout.parse("unix", "not a number", ZoneOffset.UTC));
  }

  @Test
  void goSpansAreReadInTheirOwnSpelling() {
    assertEquals(
        java.time.Duration.ofMinutes(90),
        io.akka.glance.render.CustomApiFuncs.parseGoDuration("1h30m"));
    assertEquals(
        java.time.Duration.ofMillis(1500), io.akka.glance.render.CustomApiFuncs.parseGoDuration("1500ms"));
    assertEquals(null, io.akka.glance.render.CustomApiFuncs.parseGoDuration("later"));
  }

  @Test
  void goTimesZeroValueIsTheFirstOfYearOne() {
    assertEquals(-62135596800L, GoInstant.of(GoTime.ZERO).Unix());
    assertTrue(GoTime.isZero(GoTime.ZERO));
    assertFalse(GoTime.isZero(Instant.EPOCH));
  }
}
