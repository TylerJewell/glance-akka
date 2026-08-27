package io.akka.glance.widget.kind;

import io.akka.glance.config.ConfigException;
import io.akka.glance.config.QueryParameters;
import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.GoFormat;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The next twenty-four hours where somebody is, in two-hour columns. */
public final class WeatherWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("weather.html", "widget-base.html");

  private static final List<String> TIME_LABELS_12H =
      List.of("2am", "4am", "6am", "8am", "10am", "12pm", "2pm", "4pm", "6pm", "8pm", "10pm", "12am");

  private static final List<String> TIME_LABELS_24H =
      List.of(
          "02:00", "04:00", "06:00", "08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00",
          "22:00", "00:00");

  private static final Map<String, String> COUNTRY_ABBREVIATIONS =
      Map.of("US", "United States", "USA", "United States", "UK", "United Kingdom");

  private static final Map<Integer, String> WEATHER_CODES =
      Map.ofEntries(
          Map.entry(0, "Clear Sky"),
          Map.entry(1, "Mainly Clear"),
          Map.entry(2, "Partly Cloudy"),
          Map.entry(3, "Overcast"),
          Map.entry(45, "Fog"),
          Map.entry(48, "Rime Fog"),
          Map.entry(51, "Drizzle"),
          Map.entry(53, "Drizzle"),
          Map.entry(55, "Drizzle"),
          Map.entry(56, "Drizzle"),
          Map.entry(57, "Drizzle"),
          Map.entry(61, "Rain"),
          Map.entry(63, "Moderate Rain"),
          Map.entry(65, "Heavy Rain"),
          Map.entry(66, "Freezing Rain"),
          Map.entry(67, "Freezing Rain"),
          Map.entry(71, "Snow"),
          Map.entry(73, "Moderate Snow"),
          Map.entry(75, "Heavy Snow"),
          Map.entry(77, "Snow Grains"),
          Map.entry(80, "Rain"),
          Map.entry(81, "Moderate Rain"),
          Map.entry(82, "Heavy Rain"),
          Map.entry(85, "Snow"),
          Map.entry(86, "Snow"),
          Map.entry(95, "Thunderstorm"),
          Map.entry(96, "Thunderstorm"),
          Map.entry(99, "Thunderstorm"));

  @Y("location")
  public String Location = "";

  @Y("show-area-name")
  public boolean ShowAreaName;

  @Y("hide-location")
  public boolean HideLocation;

  @Y("hour-format")
  public String HourFormat = "";

  @Y("units")
  public String Units = "";

  @Y(skip = true)
  public Place Place;

  @Y(skip = true)
  public Weather Weather;

  @Y(skip = true)
  public List<String> TimeLabels = List.of();

  /** Where the forecast is for. */
  public static final class Place {
    public String Name = "";
    public String Area = "";
    public double Latitude;
    public double Longitude;
    public String Timezone = "";
    public String Country = "";

    ZoneId zone = ZoneId.of("UTC");
  }

  /** What the forecast says. */
  public static final class Weather {
    public int Temperature;
    public int ApparentTemperature;
    public int WeatherCode;
    public int CurrentColumn;
    public int SunriseColumn;
    public int SunsetColumn;
    public List<Column> Columns = new ArrayList<>();

    public String WeatherCodeAsString() {
      return WEATHER_CODES.getOrDefault(WeatherCode, "");
    }
  }

  /** Two hours of it. */
  public static final class Column {
    public int Temperature;
    public double Scale;
    public boolean HasPrecipitation;
  }

  @Override
  public void initialize() {
    withTitle("Weather").withCacheOnTheHour();
    if (Location.isEmpty()) {
      throw new ConfigException("location is required");
    }
    if (HourFormat.isEmpty() || HourFormat.equals("12h")) {
      TimeLabels = TIME_LABELS_12H;
    } else if (HourFormat.equals("24h")) {
      TimeLabels = TIME_LABELS_24H;
    } else {
      throw new ConfigException("hour-format must be either 12h or 24h");
    }
    if (Units.isEmpty()) {
      Units = "metric";
    } else if (!Units.equals("metric") && !Units.equals("imperial")) {
      throw new ConfigException("units must be either metric or imperial");
    }
  }

  @Override
  public void update(Instant now) {
    if (Place == null) {
      Fetched<Place> place = fetchPlace();
      if (place.error() != null) {
        withError(place.error());
        scheduleEarlyUpdate(now);
        return;
      }
      Place = place.value();
    }
    var fetched = fetchWeather(now);
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    Weather = fetched.value();
  }

  /**
   * Splits what was written into the part the geocoder accepts and the administrative area
   * used to pick between what it returns, expanding the abbreviations it will not take.
   */
  public static String[] parsePlaceName(String name) {
    var parts = name.split(",", -1);
    if (parts.length == 1) {
      return new String[] {name, ""};
    }
    if (parts.length == 2) {
      return new String[] {parts[0] + ", " + expand(parts[1]), ""};
    }
    return new String[] {parts[0] + ", " + expand(parts[2]), parts[1].trim()};
  }

  private static String expand(String name) {
    return COUNTRY_ABBREVIATIONS.getOrDefault(name.trim(), name);
  }

  private Fetched<Place> fetchPlace() {
    var parsed = parsePlaceName(Location);
    String location = parsed[0];
    String area = parsed[1];
    try {
      var response =
          Requests.json(
              HttpClients.standard(),
              Requests.get(
                      Endpoints.openMeteoGeocoding
                          + "/v1/search?name="
                          + QueryParameters.encode(location)
                          + "&count=20&language=en&format=json")
                  .build());
      var results = response.path("results");
      if (!results.isArray() || results.isEmpty()) {
        return Fetched.failed(Err.of("no places found for " + location));
      }
      com.fasterxml.jackson.databind.JsonNode chosen = null;
      if (!area.isEmpty()) {
        String wanted = area.toLowerCase(Locale.ROOT);
        for (var candidate : results) {
          if (candidate.path("admin1").asText("").toLowerCase(Locale.ROOT).equals(wanted)) {
            chosen = candidate;
            break;
          }
        }
        if (chosen == null) {
          return Fetched.failed(Err.of("no place found for " + location + " in " + area));
        }
      } else {
        chosen = results.get(0);
      }
      var place = new Place();
      place.Name = chosen.path("name").asText("");
      place.Area = chosen.path("admin1").asText("");
      place.Latitude = chosen.path("latitude").asDouble();
      place.Longitude = chosen.path("longitude").asDouble();
      place.Timezone = chosen.path("timezone").asText("");
      place.Country = chosen.path("country").asText("");
      try {
        place.zone = ZoneId.of(place.Timezone);
      } catch (RuntimeException e) {
        return Fetched.failed(
            Err.of("loading location: unknown time zone " + place.Timezone));
      }
      return Fetched.of(place);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(Err.of("fetching places data: " + e.error()));
    }
  }

  private Fetched<Weather> fetchWeather(Instant now) {
    String temperatureUnit = Units.equals("imperial") ? "fahrenheit" : "celsius";
    var query = new QueryParameters();
    var values = query.values();
    values.put("latitude", List.of(GoFormat.fixed(Place.Latitude, 6)));
    values.put("longitude", List.of(GoFormat.fixed(Place.Longitude, 6)));
    values.put("timeformat", List.of("unixtime"));
    values.put("timezone", List.of(Place.Timezone));
    values.put("forecast_days", List.of("1"));
    values.put("current", List.of("temperature_2m,apparent_temperature,weather_code"));
    values.put("hourly", List.of("temperature_2m,precipitation_probability"));
    values.put("daily", List.of("sunrise,sunset"));
    values.put("temperature_unit", List.of(temperatureUnit));
    try {
      var response =
          Requests.json(
              HttpClients.standard(),
              Requests.get(Endpoints.openMeteo + "/v1/forecast?" + query.toQueryString()).build());
      var local = ZonedDateTime.ofInstant(now, Place.zone);
      int currentBar = local.getHour() / 2;
      long sunrise = response.path("daily").path("sunrise").path(0).asLong();
      long sunset = response.path("daily").path("sunset").path(0).asLong();
      int sunriseBar =
          ZonedDateTime.ofInstant(Instant.ofEpochSecond(sunrise), Place.zone).getHour() / 2;
      int sunsetBar =
          (ZonedDateTime.ofInstant(Instant.ofEpochSecond(sunset), Place.zone).getHour() - 1) / 2;
      if (sunsetBar < 0) {
        sunsetBar = 0;
      }
      var weather = new Weather();
      var current = response.path("current");
      weather.Temperature = (int) current.path("temperature_2m").asDouble();
      weather.ApparentTemperature = (int) current.path("apparent_temperature").asDouble();
      weather.WeatherCode = current.path("weather_code").asInt();
      weather.CurrentColumn = currentBar;
      weather.SunriseColumn = sunriseBar;
      weather.SunsetColumn = sunsetBar;

      var hourly = response.path("hourly");
      var temperaturesNode = hourly.path("temperature_2m");
      if (temperaturesNode.isArray() && temperaturesNode.size() == 24) {
        var precipitationNode = hourly.path("precipitation_probability");
        var temperatures = new ArrayList<Integer>(12);
        var precipitations = new ArrayList<Boolean>(12);
        for (int i = 0; i < 24; i += 2) {
          if (i / 2 == currentBar) {
            temperatures.add((int) current.path("temperature_2m").asDouble());
          } else {
            temperatures.add(
                (int)
                    GoFormat.round(
                        (temperaturesNode.get(i).asDouble() + temperaturesNode.get(i + 1).asDouble())
                            / 2));
          }
          precipitations.add(
              (precipitationNode.path(i).asInt() + precipitationNode.path(i + 1).asInt()) / 2 > 75);
        }
        int min = Collections.min(temperatures);
        int max = Collections.max(temperatures);
        double range = max - min;
        for (int i = 0; i < 12; i++) {
          var column = new Column();
          column.Temperature = temperatures.get(i);
          column.HasPrecipitation = precipitations.get(i);
          column.Scale = range > 0 ? (temperatures.get(i) - min) / range : 1;
          weather.Columns.add(column);
        }
      }
      return Fetched.of(weather);
    } catch (Fetches.FetchException e) {
      return Fetched.failed(Err.NO_CONTENT.because(e.error().message()));
    }
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
