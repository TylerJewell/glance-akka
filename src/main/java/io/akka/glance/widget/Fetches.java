package io.akka.glance.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Where a widget's fetching runs.
 *
 * <p>The original starts a goroutine per widget and, inside a widget, a pool of workers over
 * whatever it is fetching. Virtual threads are the same shape: cheap enough to start one per
 * item, and the worker count becomes a limit on how many run at once rather than how many
 * exist.
 */
public final class Fetches {

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /** {@code defaultNumWorkers}. */
  public static final int DEFAULT_WORKERS = 10;

  private Fetches() {}

  /** For a caller that needs to hand this pool the waiting rather than do it itself. */
  public static ExecutorService executor() {
    return EXECUTOR;
  }

  public static Future<?> submit(Runnable task) {
    return EXECUTOR.submit(task);
  }

  /** Starts one fetch, for a widget that runs several different ones side by side. */
  public static <T> Future<T> submitCall(Callable<T> task) {
    return EXECUTOR.submit(task);
  }

  /** Waits for one, turning whatever it threw into the error half of the outcome. */
  public static <T> Outcome<T> await(Future<T> future) {
    try {
      return new Outcome<>(future.get(), null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Outcome<>(null, Err.of("interrupted"));
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof FetchException fetch) {
        return new Outcome<>(null, fetch.error());
      }
      return new Outcome<>(null, Err.of(cause));
    }
  }

  /** One result per input, in the input's order, each either a value or the error it hit. */
  public record Outcome<O>(O value, Err error) {}

  /**
   * {@code workerPoolDo} — runs {@code task} over {@code data} with at most {@code workers}
   * running at once, and returns what each one produced.
   */
  public static <I, O> List<Outcome<O>> pool(
      List<I> data, int workers, Function<I, O> task) {
    var results = new ArrayList<Outcome<O>>(data.size());
    for (int i = 0; i < data.size(); i++) {
      results.add(null);
    }
    if (data.isEmpty()) {
      return results;
    }
    if (data.size() == 1) {
      results.set(0, run(task, data.getFirst()));
      return results;
    }
    int limit = workers == 0 ? DEFAULT_WORKERS : Math.min(workers, data.size());
    var permits = new Semaphore(limit);
    var futures = new ArrayList<Future<Outcome<O>>>(data.size());
    for (var item : data) {
      Callable<Outcome<O>> call =
          () -> {
            permits.acquire();
            try {
              return run(task, item);
            } finally {
              permits.release();
            }
          };
      futures.add(EXECUTOR.submit(call));
    }
    for (int i = 0; i < futures.size(); i++) {
      try {
        results.set(i, futures.get(i).get());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        results.set(i, new Outcome<>(null, Err.of("interrupted")));
      } catch (ExecutionException e) {
        results.set(i, new Outcome<>(null, Err.of(e.getCause())));
      }
    }
    return results;
  }

  private static <I, O> Outcome<O> run(Function<I, O> task, I input) {
    try {
      return new Outcome<>(task.apply(input), null);
    } catch (FetchException e) {
      return new Outcome<>(null, e.error());
    } catch (RuntimeException e) {
      return new Outcome<>(null, Err.of(e));
    }
  }

  /** How a fetch reports a failure it wants recorded as the widget's own error. */
  public static final class FetchException extends RuntimeException {
    private final Err error;

    public FetchException(Err error) {
      super(error.message(), null, false, false);
      this.error = error;
    }

    public FetchException(String message) {
      this(Err.of(message));
    }

    public Err error() {
      return error;
    }
  }
}
