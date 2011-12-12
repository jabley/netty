/*
 * Copyright 2012 The Netty Project
 * 
 * The Netty Project licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

/**
 * 
 * @see http://tools.ietf.org/html/rfc2616#section-14.35.1
 */
class ByteRangeSet {

  @SuppressWarnings("unchecked")
  static final ByteRangeSet NO_RANGE_HEADER = new ByteRangeSet(Collections.EMPTY_LIST);

  private final RangeSpec[] specs;

  ByteRangeSet(List<RangeSpec> specs) {
    this.specs = merge(specs);
  }

  public static ByteRangeSet parse(String byteRangeSet, int contentLength) {
    if (byteRangeSet == null || !byteRangeSet.contains("-")) {
      throw new SyntacticallyInvalidByteRangeException("Invalid range-spec: " + byteRangeSet);
    }

    StringTokenizer csv = new StringTokenizer(byteRangeSet, ",");

    List<RangeSpec> specs = new ArrayList<RangeSpec>();

    while (csv.hasMoreTokens()) {
      specs.add(new RangeSpec(csv.nextToken(), contentLength));
    }

    return new ByteRangeSet(specs);
  }

  public int size() {
    return this.specs.length;
  }

  public RangeSpec get(int i) {
    return this.specs[i];
  }

  private RangeSpec[] merge(List<RangeSpec> specs) {

    // Hopefully optimize the common case.
    if (specs.size() == 1) {
      return new RangeSpec[] { specs.get(0) };
    }

    List<RangeSpec> result = new ArrayList<RangeSpec>(specs.size());

    RangeSpec previous = null;

    int n = 0; // maintain a count of successfully merged

    for (RangeSpec current : specs) {

      if (previous != null) {
        RangeSpec merged = previous.attemptMerge(current);

        if (merged != null) {
          previous = merged;
          ++n;
        } else {
          // Can't be merged, try the next one
          result.add(previous);
          previous = current;
        }
      } else {
        // First time through the loop
        previous = current;
      }
    }

    /* Add the last item to the result. */
    if (previous != null) {
      result.add(previous);
    }

    if (n > 0) {

      // We merged something. We might have new candidates suitable for
      // merging.
      result = Arrays.asList(merge(result));
    }

    return result.toArray(new RangeSpec[result.size()]);
  }
}
