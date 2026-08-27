package io.akka.glance.widget.kind;

import io.akka.glance.config.Y;
import io.akka.glance.gotemplate.Safe;
import io.akka.glance.gotemplate.Template;
import io.akka.glance.net.Endpoints;
import io.akka.glance.net.HttpClients;
import io.akka.glance.net.Requests;
import io.akka.glance.render.Templates;
import io.akka.glance.util.Charts;
import io.akka.glance.util.Text;
import io.akka.glance.widget.Err;
import io.akka.glance.widget.Fetched;
import io.akka.glance.widget.Fetches;
import io.akka.glance.widget.Widget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Prices, and how each has moved since the day before. */
public final class MarketsWidget extends Widget {

  /** Parsed once, the way the original keeps each template in a package variable. */
  private static final Template TEMPLATE = Templates.of("markets.html", "widget-base.html");

  /** How many closing prices the small chart draws. */
  private static final int CHART_DAYS = 21;

  private static final Map<String, String> CURRENCY_SYMBOLS =
      Map.ofEntries(
          Map.entry("USD", "$"),
          Map.entry("EUR", "€"),
          Map.entry("JPY", "¥"),
          Map.entry("CAD", "C$"),
          Map.entry("AUD", "A$"),
          Map.entry("GBP", "£"),
          Map.entry("CHF", "Fr"),
          Map.entry("NZD", "N$"),
          Map.entry("INR", "₹"),
          Map.entry("BRL", "R$"),
          Map.entry("RUB", "₽"),
          Map.entry("TRY", "₺"),
          Map.entry("ZAR", "R"),
          Map.entry("CNY", "¥"),
          Map.entry("KRW", "₩"),
          Map.entry("HKD", "HK$"),
          Map.entry("SGD", "S$"),
          Map.entry("SEK", "kr"),
          Map.entry("NOK", "kr"),
          Map.entry("DKK", "kr"),
          Map.entry("PLN", "zł"),
          Map.entry("PHP", "₱"));

  @Y("stocks")
  public List<MarketRequest> StocksRequests = new ArrayList<>();

  @Y("markets")
  public List<MarketRequest> MarketRequests = new ArrayList<>();

  @Y("chart-link-template")
  public String ChartLinkTemplate = "";

  @Y("symbol-link-template")
  public String SymbolLinkTemplate = "";

  @Y("sort-by")
  public String Sort = "";

  @Y(skip = true)
  public List<Market> Markets = new ArrayList<>();

  /** One symbol to follow. */
  public static class MarketRequest {
    @Y("name")
    public String CustomName = "";

    @Y("symbol")
    public String Symbol = "";

    @Y("chart-link")
    public String ChartLink = "";

    @Y("symbol-link")
    public String SymbolLink = "";
  }

  /** One symbol's price, as the template shows it. */
  public static final class Market extends MarketRequest {
    public String Name = "";
    public String Currency = "";
    public String CurrencySymbol = "";
    public double Price;
    public int PriceHint;
    public double PercentChange;
    public String SvgChartPoints = "";
  }

  @Override
  public void initialize() {
    withTitle("Markets").withCacheDuration(Duration.ofHours(1));
    // "stocks" was the setting's first name and still works.
    if (MarketRequests.isEmpty()) {
      MarketRequests = StocksRequests;
    }
    for (var request : MarketRequests) {
      if (!ChartLinkTemplate.isEmpty() && request.ChartLink.isEmpty()) {
        request.ChartLink = ChartLinkTemplate.replace("{SYMBOL}", request.Symbol);
      }
      if (!SymbolLinkTemplate.isEmpty() && request.SymbolLink.isEmpty()) {
        request.SymbolLink = SymbolLinkTemplate.replace("{SYMBOL}", request.Symbol);
      }
    }
  }

  @Override
  public void update(Instant now) {
    var fetched = fetch();
    if (!canContinueUpdateAfterHandlingErr(fetched.error(), now)) {
      return;
    }
    var markets = fetched.value();
    if (Sort.equals("absolute-change")) {
      markets.sort(
          Comparator.comparingDouble((Market market) -> Math.abs(market.PercentChange)).reversed());
    } else if (Sort.equals("change")) {
      markets.sort(Comparator.comparingDouble((Market market) -> market.PercentChange).reversed());
    }
    Markets = markets;
  }

  private Fetched<List<Market>> fetch() {
    var results =
        Fetches.pool(
            MarketRequests,
            0,
            request ->
                Requests.json(
                    HttpClients.standard(),
                    Requests.get(
                            Endpoints.yahooFinance
                                + "/v8/finance/chart/"
                                + request.Symbol
                                + "?range=1mo&interval=1d")
                        .header("User-Agent", HttpClients.browserUserAgent())
                        .build()));
    var markets = new ArrayList<Market>(results.size());
    int failed = 0;
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      if (result.error() != null) {
        failed++;
        continue;
      }
      var chartResults = result.value().path("chart").path("result");
      if (!chartResults.isArray() || chartResults.isEmpty()) {
        failed++;
        continue;
      }
      var chart = chartResults.get(0);
      var meta = chart.path("meta");
      var prices = new ArrayList<Double>();
      for (var price : chart.path("indicators").path("quote").path(0).path("close")) {
        prices.add(price.isNull() ? 0.0 : price.asDouble());
      }
      if (prices.size() > CHART_DAYS) {
        prices = new ArrayList<>(prices.subList(prices.size() - CHART_DAYS, prices.size()));
      }
      double current = meta.path("regularMarketPrice").asDouble();
      double previous = current;
      if (prices.size() >= 2 && prices.get(prices.size() - 2) != 0) {
        previous = prices.get(prices.size() - 2);
      }
      var request = MarketRequests.get(i);
      var market = new Market();
      market.CustomName = request.CustomName;
      market.Symbol = request.Symbol;
      market.ChartLink = request.ChartLink;
      market.SymbolLink = request.SymbolLink;
      market.Price = current;
      market.Currency = meta.path("currency").asText("");
      market.CurrencySymbol = CURRENCY_SYMBOLS.getOrDefault(market.Currency, "");
      market.PriceHint = meta.path("priceHint").asInt();
      market.Name =
          request.CustomName.isEmpty() ? meta.path("shortName").asText("") : request.CustomName;
      market.PercentChange = Text.percentChange(current, previous);
      market.SvgChartPoints =
          Charts.svgPolylineCoords(100, 50, Charts.withoutZeroValues(prices));
      markets.add(market);
    }
    if (markets.isEmpty()) {
      return Fetched.failed(Err.NO_CONTENT);
    }
    if (failed > 0) {
      return Fetched.of(
          markets,
          Err.PARTIAL_CONTENT.because("could not fetch data for " + failed + " market(s)"));
    }
    return Fetched.of(markets);
  }

  @Override
  public Safe Render() {
    return renderTemplate(this, TEMPLATE);
  }
}
