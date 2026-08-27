package io.akka.glance.util;

import io.akka.glance.gotemplate.GoFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The one drawing the server does: a line of points for a small inline chart. */
public final class Charts {

  private Charts() {}

  /**
   * {@code svgPolylineCoordsFromYValues} — the values spread across the width, scaled so
   * that the smallest sits at the bottom and the largest at the top, with a little room at
   * each end.
   */
  public static String svgPolylineCoords(double width, double height, List<Double> values) {
    if (values.size() < 2) {
      return "";
    }
    double verticalPadding = height * 0.02;
    height -= verticalPadding * 2;
    double distance = width / (values.size() - 1);
    double min = Collections.min(values);
    double max = Collections.max(values);
    var out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(' ');
      }
      out.append(GoFormat.fixed(i * distance, 2))
          .append(',')
          .append(GoFormat.fixed(((max - values.get(i)) / (max - min)) * height + verticalPadding, 2));
    }
    return out.toString();
  }

  /**
   * {@code maybeCopySliceWithoutZeroValues} — the same list when nothing in it is zero, and
   * a copy without the zeros otherwise.
   */
  public static List<Double> withoutZeroValues(List<Double> values) {
    if (values.isEmpty()) {
      return values;
    }
    for (var value : values) {
      if (value == 0) {
        var out = new ArrayList<Double>(values.size() - 1);
        for (var kept : values) {
          if (kept != 0) {
            out.add(kept);
          }
        }
        return out;
      }
    }
    return values;
  }
}
