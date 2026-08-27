package io.akka.glance.widget;

import java.util.function.Function;

/**
 * What a widget needs from the application around it.
 *
 * <p>One thing, so far: the address of a static asset, which the releases widget uses for
 * each source's mark.
 */
public record Providers(Function<String, String> assetResolver) {}
