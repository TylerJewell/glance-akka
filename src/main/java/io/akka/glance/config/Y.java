package io.akka.glance.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a field is called in the configuration file, standing in for Go's {@code yaml:"..."}
 * struct tag.
 *
 * <p>Without one, the key is the field's own name in lower case, which is what {@code
 * yaml.v3} defaults to. {@code inline} spreads a nested object's keys into the enclosing
 * mapping, and {@code skip} keeps a field out of the file entirely.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Y {

  String value() default "";

  boolean inline() default false;

  boolean skip() default false;
}
