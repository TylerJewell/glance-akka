package io.akka.glance.util;

import java.util.function.Supplier;

/**
 * One call at a time, and everybody who asks while it is running gets its answer.
 *
 * <p>Not a cache: the next caller after it finishes starts a new one. What it prevents is the
 * same work being started several times at once, which is what a page full of widgets asking
 * the same question would otherwise do.
 */
public final class Singleflight<T> {

  private final Supplier<T> supplier;
  private final Object lock = new Object();
  private Call current;

  public Singleflight(Supplier<T> supplier) {
    this.supplier = supplier;
  }

  private final class Call {
    T value;
    RuntimeException failure;
    boolean done;
  }

  public T get() {
    Call call;
    boolean mine = false;
    synchronized (lock) {
      if (current == null) {
        current = new Call();
        mine = true;
      }
      call = current;
    }
    if (!mine) {
      synchronized (call) {
        while (!call.done) {
          try {
            call.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a shared call");
          }
        }
      }
      if (call.failure != null) {
        throw call.failure;
      }
      return call.value;
    }
    try {
      call.value = supplier.get();
    } catch (RuntimeException e) {
      call.failure = e;
    } finally {
      synchronized (lock) {
        current = null;
      }
      synchronized (call) {
        call.done = true;
        call.notifyAll();
      }
    }
    if (call.failure != null) {
      throw call.failure;
    }
    return call.value;
  }
}
