package io.netty.handler.codec.http;

import org.junit.Test;
import static org.junit.Assert.*;

public class RangeSpecTest {

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void emptyRangeSpecIsInvalid() {
    new RangeSpec("", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void whitespaceRangeSpecIsInvalid() {
    new RangeSpec("           ", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void boundedRangeWithEndAfterStartIsInvalid() {
    new RangeSpec("500-499", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void mustContainValidFormat() {
    new RangeSpec("500", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void negativeSuffixValueIsInvalid() {
    new RangeSpec("--500", 1000);
  }

  @Test
  public void validSuffixRange() {
    RangeSpec spec = new RangeSpec("-500", 1000);
    assertEquals(500, spec.start);
    assertEquals(999, spec.end);
    assertEquals(500, spec.length);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void boundedRangeWithNegativeSuffixIsInvalid() {
    new RangeSpec("500--999", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void negativeStartForBoundedRangeIsInvalid() {
    new RangeSpec("-500-999", 1000);
  }

  @Test(expected = SyntacticallyInvalidByteRangeException.class)
  public void tooManyRangesIsInvalid() {
    new RangeSpec("500-699-799", 1000);
  }

  @Test
  public void simpleMergeOfAdjacentByteRangeSpecs() {
    RangeSpec first = new RangeSpec("0-499", 1000);
    RangeSpec second = new RangeSpec("500-999", 1000);

    RangeSpec merged = first.attemptMerge(second);
    assertNotNull(merged);
  }

  @Test
  public void mergeOfContainedByteRangeSpecs() {
    RangeSpec first = new RangeSpec("0-499", 1000);
    RangeSpec second = new RangeSpec("400-449", 1000);

    RangeSpec merged = first.attemptMerge(second);
    assertNotNull(merged);
  }

  @Test
  public void mergeOfOverlappingByteRangeSpecs() {
    RangeSpec first = new RangeSpec("500-599", 1000);
    RangeSpec second = new RangeSpec("400-502", 1000);

    RangeSpec merged = first.attemptMerge(second);
    assertNotNull(merged);
  }

}
