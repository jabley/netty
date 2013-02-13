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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedByteChannel;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

/**
 * Attempts to honour a Range request header by inspecting the incoming header
 * on the request and filtering the response output appropriately.
 */
public class HttpRangeChunker extends MessageToMessageCodec<HttpMessage, HttpMessage> {

  private volatile EmbeddedByteChannel encoder;

  private final Queue<Object> rangeQueue = new ArrayDeque<Object>();

  private static final Object NO_RANGE_HEADER = new Object();

  private HttpMessage message;

  /**
   * {@inheritDoc}
   */
  @Override
  protected Object encode(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
    if (msg instanceof HttpResponse && ((HttpResponse) msg).getStatus().code() != 200) {
      // Non-200 responses should not have Range processing applied
      return msg;
    }

    // handle the case of single complete message without content
    if (msg instanceof FullHttpMessage && !((FullHttpMessage) msg).data().isReadable()) {

      // Remove content encoding
      Object range = rangeQueue.poll();
      if (range == null) {
        throw new IllegalStateException("cannot send more responses than requests");
      }

      return ((FullHttpMessage) msg).retain();
    }

    if (msg instanceof HttpMessage) {
      assert message == null;

      // check if this message is also of type HttpContent is such case just
      // make a safe copy of the headers
      // as the content will get handled later and this simplify the handling
      if (msg instanceof HttpContent) {
        if (msg instanceof HttpRequest) {
          HttpRequest req = (HttpRequest) msg;
          message = new DefaultHttpRequest(req.getProtocolVersion(), req.getMethod(), req.getUri());
          message.headers().set(req.headers());
        } else if (msg instanceof HttpResponse) {
          HttpResponse res = (HttpResponse) msg;
          message = new DefaultHttpResponse(res.getProtocolVersion(), res.getStatus());
          message.headers().set(res.headers());
        } else {
          return msg;
        }
      } else {
        message = msg;
      }

      cleanup();
    }

    HttpMessage m = msg;

    String contentRange = HttpHeaders.getHeader(m, HttpHeaders.Names.CONTENT_RANGE);

    if (contentRange != null) {

      // Something has already set the Content-Range header, so don't do any
      // processing in here.
      return msg;
    }

    if (msg instanceof HttpContent) {
      HttpContent c = (HttpContent) msg;

      Object rangeMarker = rangeQueue.poll();

      if (rangeMarker == null) {
        throw new IllegalStateException("cannot send more responses than requests");
      }

      if (rangeMarker == NO_RANGE_HEADER) {

        // Not a Range request - ignore
        return msg;
      }

      HttpMessage message = this.message;
      HttpHeaders headers = message.headers();
      this.message = null;

      // TODO: Support If-Range conditional check

      boolean hasContent = c.data().isReadable();

      String range = (String) rangeMarker;

      // TODO: readableBytes probably not quite right - we want the full size of
      // the representation
      ByteRangeSet brs = ByteRangeSet.parse(range.substring(6), c.data().readableBytes());

      if (hasContent && (encoder = newContentRangeEncoder(brs)) != null) {

        if (m instanceof HttpResponse) {
          ((HttpResponse) m).setStatus(HttpResponseStatus.PARTIAL_CONTENT);
        }

        if (!HttpHeaders.isTransferEncodingChunked(m)) {
          ByteBuf content = c.data();

          if (brs.size() == 1) {

            // Encode the content.
            ByteBuf newContent = Unpooled.buffer();
            encode(content, newContent);
            finishEncode(newContent);

            // Replace the content.
            c = new DefaultHttpContent(newContent);

            // Set the headers
            headers.set(HttpHeaders.Names.CONTENT_RANGE, brs.get(0).asContentRange());

            headers
                .set(HttpHeaders.Names.CONTENT_LENGTH, Integer.toString(content.readableBytes()));

            return new Object[] { msg, c };
          } else {
            assert brs.size() > 1;

            throw new UnsupportedOperationException("Not implemented");

            // TODO: Need to send a multipart/byteranges
            // response.
          }
        }
      }
      // Because HttpMessage is a mutable object, we can simply
      // forward the write request.
      return msg;
    } else {
      return msg;
    }
  }

  private void cleanup() {
    if (encoder != null) {
      // Clean-up the previous encoder if not cleaned up correctly.
      finishEncode(Unpooled.buffer());
    }
  }

  private void encode(ByteBuf in, ByteBuf out) {
    encoder.writeOutbound(in);
    fetchEncoderOutput(out);
  }

  private void finishEncode(ByteBuf out) {
    if (encoder.finish()) {
      fetchEncoderOutput(out);
    }
    encoder = null;
  }

  private void fetchEncoderOutput(ByteBuf out) {
    for (;;) {
      ByteBuf buf = encoder.readOutbound();
      if (buf == null) {
        break;
      }
      out.writeBytes(buf);
    }
  }

  private EmbeddedByteChannel newContentRangeEncoder(ByteRangeSet brs) {
    return new EmbeddedByteChannel(new RangeEncoder(brs));
  }

  static final class RangeEncoder extends MessageToMessageEncoder<ByteBuf> {

    private ChannelHandlerContext ctx;

    private final ByteRangeSet brs;

    private RangeEncoder(ByteRangeSet brs) {
      this.brs = brs;
    }

    @Override
    public void beforeAdd(ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ByteBuf encode(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
      ByteBuf result = msg;

      ByteBuf raw = msg;
      byte[] in = new byte[raw.readableBytes()];
      raw.readBytes(in);

      RangeSpec spec = brs.get(0);

      byte[] out = Arrays.copyOfRange(in, spec.start, spec.length);
      // FIXME:
      // result = ctx.channel().config().bufferFactory()
      // .getBuffer(raw.order(), out, 0, spec.length);

      return result;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected HttpMessage decode(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
    String range = HttpHeaders.getHeader(msg, HttpHeaders.Names.RANGE);

    Object rangeMarker;

    if (range == null) {
      rangeMarker = NO_RANGE_HEADER;
    } else if (range.startsWith("bytes=")) {
      rangeMarker = range;
    } else {
      // TODO: really want to return a response indicating bad range
      // request? Maybe have another marker for invalid range request which
      // #encode can handle?
      throw new SyntacticallyInvalidByteRangeException(range);
    }

    boolean offered = rangeQueue.offer(rangeMarker);
    assert offered;

    return msg;
  }

}
