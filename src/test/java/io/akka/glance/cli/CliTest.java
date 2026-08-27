package io.akka.glance.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.favre.lib.crypto.bcrypt.BCrypt;
import io.akka.glance.config.ConfigException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands the program answers to, and what each one prints. */
class CliTest {

  private record Run(int status, String output) {}

  private static Run run(String... args) {
    var captured = new ByteArrayOutputStream();
    int status = Cli.main(args, new PrintStream(captured, true, StandardCharsets.UTF_8));
    return new Run(status, captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  void noArgumentsMeansServing() {
    assertEquals(Cli.Intent.SERVE, Cli.parse(new String[] {}).intent());
  }

  @Test
  void theVersionIsAskedForThreeWays() {
    for (var spelling : new String[] {"--version", "-v", "version"}) {
      assertEquals(Cli.Intent.VERSION_PRINT, Cli.parse(new String[] {spelling}).intent());
    }
    assertEquals("dev\n", run("--version").output().replace("\r\n", "\n"));
  }

  @Test
  void theConfigPathIsTakenFromItsFlagOrDefaulted() {
    assertEquals("glance.yml", Cli.parse(new String[] {"config:print"}).configPath());
    assertEquals(
        "other.yml", Cli.parse(new String[] {"-config", "other.yml", "config:print"}).configPath());
    assertEquals(
        "other.yml", Cli.parse(new String[] {"--config=other.yml", "config:print"}).configPath());
  }

  @Test
  void everyCommandIsRecognised() {
    assertEquals(Cli.Intent.CONFIG_VALIDATE, Cli.parse(new String[] {"config:validate"}).intent());
    assertEquals(Cli.Intent.CONFIG_PRINT, Cli.parse(new String[] {"config:print"}).intent());
    assertEquals(Cli.Intent.SENSORS_PRINT, Cli.parse(new String[] {"sensors:print"}).intent());
    assertEquals(Cli.Intent.DIAGNOSE, Cli.parse(new String[] {"diagnose"}).intent());
    assertEquals(Cli.Intent.SECRET_MAKE, Cli.parse(new String[] {"secret:make"}).intent());
    assertEquals(
        Cli.Intent.PASSWORD_HASH, Cli.parse(new String[] {"password:hash", "a"}).intent());
    assertEquals(
        Cli.Intent.MOUNTPOINT_INFO, Cli.parse(new String[] {"mountpoint:info", "/"}).intent());
  }

  @Test
  void anUnknownCommandIsRefusedWithItsOwnName() {
    assertEquals(
        "unknown command: nonesuch",
        assertThrows(ConfigException.class, () -> Cli.parse(new String[] {"nonesuch"}))
            .getMessage());
  }

  @Test
  void aValidConfigurationValidatesQuietly(@TempDir Path directory) throws Exception {
    var file = directory.resolve("glance.yml");
    Files.writeString(
        file,
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  - type: calendar
        """);
    var result = run("-config", file.toString(), "config:validate");
    assertEquals(0, result.status());
    assertEquals("", result.output());
  }

  @Test
  void anInvalidConfigurationIsNamedAsSuch(@TempDir Path directory) throws Exception {
    var file = directory.resolve("glance.yml");
    Files.writeString(file, "pages: []\n");
    var result = run("-config", file.toString(), "config:validate");
    assertEquals(1, result.status());
    assertTrue(result.output().startsWith("Config file is invalid: no pages configured"));
  }

  @Test
  void aMissingConfigurationIsNamedAsSuch(@TempDir Path directory) {
    var result = run("-config", directory.resolve("absent.yml").toString(), "config:validate");
    assertEquals(1, result.status());
    assertTrue(result.output().startsWith("Could not parse config file:"));
  }

  @Test
  void printingAConfigurationPastesItsIncludesIn(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("widgets.yml"), "- type: calendar\n");
    var file = directory.resolve("glance.yml");
    Files.writeString(
        file,
        """
        pages:
          - name: Home
            columns:
              - size: full
                widgets:
                  $include: widgets.yml
        """);
    var result = run("-config", file.toString(), "config:print");
    assertEquals(0, result.status());
    assertTrue(result.output().contains("- type: calendar"));
    assertTrue(!result.output().contains("$include"));
  }

  @Test
  void aSecretKeyIsTheRightLength() {
    var result = run("secret:make");
    assertEquals(0, result.status());
    assertEquals(64, Base64.getDecoder().decode(result.output().trim()).length);
  }

  @Test
  void aHashedPasswordCanBeCheckedAgainstItsPassword() {
    var result = run("password:hash", "correct-horse");
    assertEquals(0, result.status());
    assertTrue(
        BCrypt.verifyer()
            .verify(
                "correct-horse".getBytes(StandardCharsets.UTF_8),
                result.output().trim().getBytes(StandardCharsets.UTF_8))
            .verified);
  }

  @Test
  void aPasswordThatIsTooShortIsRefused() {
    assertEquals(1, run("password:hash", "abc").status());
    assertTrue(run("password:hash", "abc").output().startsWith("Password must be at least 6"));
    assertEquals(1, run("password:hash", "").status());
    assertTrue(run("password:hash", "").output().startsWith("Password cannot be empty"));
  }

  @Test
  void mountpointInfoDescribesAPathThatExists(@TempDir Path directory) {
    var result = run("mountpoint:info", directory.toString());
    assertEquals(0, result.status());
    assertTrue(result.output().contains("Path: " + directory));
    assertTrue(result.output().contains("Used percent:"));
  }

  @Test
  void mountpointInfoRefusesAPathThatDoesNot(@TempDir Path directory) {
    var result = run("mountpoint:info", directory.resolve("absent").toString());
    assertEquals(1, result.status());
    assertTrue(result.output().startsWith("Failed to retrieve info for path"));
  }

  @Test
  void theUsageTextNamesEveryCommand() {
    String usage = Cli.usage();
    for (var command :
        new String[] {
          "config:validate", "config:print", "password:hash", "secret:make", "sensors:print",
          "mountpoint:info", "diagnose"
        }) {
      assertTrue(usage.contains(command), command + " is not in the usage text");
    }
  }
}
