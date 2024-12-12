package com.linkedin.alpini.base.test;

import com.linkedin.alpini.base.misc.Time;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.Assert;


/**
 * @author Antony T Curtis {@literal <acurtis@linkedin.com>}
 */
public enum TestUtil {
  SINGLETON; // Effective Java, Item 3 - Enum singleton

  private static final Logger LOGGER = LogManager.getLogger(TestUtil.class);

  public static void waitFor(BooleanSupplier test, long duration, TimeUnit unit) {
    long timeout = Time.nanoTime() + unit.toNanos(duration);
    do {
      if (test.getAsBoolean()) {
        break;
      }
      Thread.yield();
    } while (Time.nanoTime() < timeout);
  }

  public static boolean sleep(long millis) {
    try {
        Time.sleep(millis);
        return true;
    } catch (InterruptedException ie) {
        return false;
    }
  }

  public static String dequalifyClassName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private static String getCallingMethod() {
    return Arrays.stream(Thread.currentThread().getStackTrace())
        .filter(
            frame -> frame.getClassName().startsWith("com.linkedin.")
                && !frame.getClassName().equals(TestUtil.class.getName()))
        .findFirst()
        .map(
            frame -> String.format(
                "%s.%s.%d",
                dequalifyClassName(frame.getClassName()),
                frame.getMethodName(),
                frame.getLineNumber()))
        .orElse("UNKNOWN_METHOD");
  }

  /** In milliseconds */
  private static final long ND_ASSERTION_MIN_WAIT_TIME_MS = 100;
  private static final long ND_ASSERTION_MAX_WAIT_TIME_MS = 3000;

  public interface NonDeterministicAssertion {
    void execute() throws Exception;
  }

  /**
   * To be used for tests when we need to wait for an async operation to complete. Pass a timeout, and a labmda
   * for checking if the operation is complete.
   *
   * There is an issue within Mockito where it emits VerifyError instead of ArgumentsAreDifferent Exception.
   * Check out "ExceptionFactory#JunitArgsAreDifferent" for details. The workaround here is to catch both
   * assert and verify error.
   * TODO: find a better way resolve it
   *
   * @param timeout amount of time to wait
   * @param timeoutUnit {@link TimeUnit} for the {@param timeout}
   * @param assertion A {@link NonDeterministicAssertion} which should simply execute without exception
   *                           if it is successful, or throw an {@link AssertionError} otherwise.
   * @throws AssertionError throws the exception thrown by the {@link NonDeterministicAssertion} if the maximum
   *                        wait time has been exceeded.
   */
  public static void waitForNonDeterministicAssertion(
      long timeout,
      TimeUnit timeoutUnit,
      NonDeterministicAssertion assertion) throws AssertionError {
    waitForNonDeterministicAssertion(timeout, timeoutUnit, false, assertion);
  }

  public static void waitForNonDeterministicAssertion(
      long timeout,
      TimeUnit timeoutUnit,
      boolean exponentialBackOff,
      NonDeterministicAssertion assertion) throws AssertionError {
    waitForNonDeterministicAssertion(timeout, timeoutUnit, exponentialBackOff, false, assertion);
  }

  public static void waitForNonDeterministicAssertion(
      long timeout,
      TimeUnit timeoutUnit,
      boolean exponentialBackOff,
      boolean retryOnThrowable,
      NonDeterministicAssertion assertion) throws AssertionError {
    long startTimeMs = System.currentTimeMillis();
    long nextDelayMs = ND_ASSERTION_MIN_WAIT_TIME_MS;
    long deadlineMs = startTimeMs + timeoutUnit.toMillis(timeout);
    try {
      for (;;) {
        try {
          assertion.execute();
          return;
        } catch (Throwable e) {
          long remainingMs = deadlineMs - System.currentTimeMillis();
          if (remainingMs < nextDelayMs || !(retryOnThrowable || e instanceof AssertionError)) {
            throw (e instanceof AssertionError ? (AssertionError) e : new AssertionError(e));
          }
          LOGGER.info("Non-deterministic assertion not met: {}. Will retry again in {} ms.", e, nextDelayMs);
          Assert.assertTrue(sleep(nextDelayMs), "Waiting for non-deterministic assertion was interrupted.");
          if (exponentialBackOff) {
            nextDelayMs = Math.min(nextDelayMs * 2, remainingMs - nextDelayMs);
            nextDelayMs = Math.min(nextDelayMs, ND_ASSERTION_MAX_WAIT_TIME_MS);
          }
        }
      }
    } finally {
      LOGGER.info("{} waiting took {} ms.", getCallingMethod(), System.currentTimeMillis() - startTimeMs);
    }
  }
}
