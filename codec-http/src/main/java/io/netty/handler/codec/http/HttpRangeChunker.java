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

import io.netty.buffer.ChannelBuffer;
import io.netty.buffer.ChannelBuffers;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.LifeCycleAwareChannelHandler;
import io.netty.channel.MessageEvent;
import io.netty.channel.SimpleChannelHandler;
import io.netty.handler.codec.embedder.EncoderEmbedder;
import io.netty.handler.codec.oneone.OneToOneEncoder;
import io.netty.util.internal.QueueFactory;

import java.util.Arrays;
import java.util.Queue;

/**
 * Attempts to honour a Range request header by inspecting the incoming header
 * on the request and filtering the response output appropriately.
 */
public class HttpRangeChunker extends SimpleChannelHandler {

  private final Queue<Object> rangeQueue = QueueFactory.createQueue(Object.class);

  private static final Object NO_RANGE_HEADER = new Object();

  private volatile EncoderEmbedder<ChannelBuffer> encoder;

  /**
   * {@inheritDoc}
   */
  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    Object msg = e.getMessage();

    if (!(msg instanceof HttpMessage)) {
      ctx.sendUpstream(e);
      return;
    }

    HttpMessage m = (HttpMessage) msg;
    String range = m.getHeader(HttpHeaders.Names.RANGE);

    Object rangeMarker;

    if (range == null) {
      rangeMarker = NO_RANGE_HEADER;
    } else if (range.startsWith("bytes=")) {
      rangeMarker = range;
    } else {

      // TODO: really want to return a response indicating bad range
      // request?
      throw new SyntacticallyInvalidByteRangeException(range);
    }

    boolean offered = rangeQueue.offer(rangeMarker);
    assert offered;

    ctx.sendUpstream(e);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    Object msg = e.getMessage();

    if (msg instanceof HttpResponse && ((HttpResponse) msg).getStatus().getCode() != 200) {
      // Non-200 responses should not have Range processing applied
      ctx.sendDownstream(e);
    } else if (msg instanceof HttpMessage) {
      HttpMessage m = (HttpMessage) msg;

      String contentRange = m.getHeader(HttpHeaders.Names.CONTENT_RANGE);

      if (contentRange != null) {
        // Something has already set the Content-Range header, so don't
        // do any processing in here.
        ctx.sendDownstream(e);
      } else {

        Object rangeMarker = rangeQueue.poll();

        if (rangeMarker == null) {
          throw new IllegalStateException("cannot send more responses than requests");
        }

        if (rangeMarker == NO_RANGE_HEADER) {
          ctx.sendDownstream(e);
        } else {

          // TODO: Support If-Range conditional check

          boolean hasContent = m.isChunked() || m.getContent().readable();

          String range = (String) rangeMarker;

          ByteRangeSet brs = ByteRangeSet.parse(range.substring(6), m.getContent().readableBytes());

          if (hasContent && (encoder = newContentRangeEncoder(brs)) != null) {

            if (m instanceof HttpResponse) {
              ((HttpResponse) m).setStatus(HttpResponseStatus.PARTIAL_CONTENT);
            }

            if (!m.isChunked()) {
              ChannelBuffer content = m.getContent();

              if (brs.size() == 1) {

                // Encode the content.
                content = ChannelBuffers.wrappedBuffer(encode(content), finishEncode());

                // Replace the content.
                m.setContent(content);
                m.setHeader(HttpHeaders.Names.CONTENT_RANGE, brs.get(0).asContentRange());
                if (m.containsHeader(HttpHeaders.Names.CONTENT_LENGTH)) {
                  m.setHeader(HttpHeaders.Names.CONTENT_LENGTH,
                      Integer.toString(content.readableBytes()));
                }
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
          ctx.sendDownstream(e);
        }
      }
    } else if (msg instanceof HttpChunk) {
      ctx.sendDownstream(e);

      // HttpChunk c = (HttpChunk) msg;
      // ChannelBuffer content = c.getContent();
      //
      // // Encode the chunk if necessary.
      // if (encoder != null) {
      // if (!c.isLast()) {
      // content = encode(content);
      // if (content.readable()) {
      // c.setContent(content);
      // ctx.sendDownstream(e);
      // }
      // } else {
      // ChannelBuffer lastProduct = finishEncode();
      //
      // // Generate an additional chunk if the decoder produced
      // // the last product on closure,
      // if (lastProduct.readable()) {
      // Channels.write(ctx, Channels.succeededFuture(e.getChannel()),
      // new DefaultHttpChunk(lastProduct), e.getRemoteAddress());
      // }
      //
      // // Emit the last chunk.
      // ctx.sendDownstream(e);
      // }
      // } else {
      // ctx.sendDownstream(e);
      // }
    } else {
      ctx.sendDownstream(e);
    }
  }

  private ChannelBuffer encode(ChannelBuffer buf) {
    encoder.offer(buf);
    return ChannelBuffers.wrappedBuffer(encoder.pollAll(new ChannelBuffer[encoder.size()]));
  }

  private ChannelBuffer finishEncode() {
    ChannelBuffer result;

    if (encoder.finish()) {
      result = ChannelBuffers.wrappedBuffer(encoder.pollAll(new ChannelBuffer[encoder.size()]));
    } else {
      result = ChannelBuffers.EMPTY_BUFFER;
    }

    encoder = null;

    return result;
  }

  private EncoderEmbedder<ChannelBuffer> newContentRangeEncoder(ByteRangeSet brs) {
    return new EncoderEmbedder<ChannelBuffer>(new RangeEncoder(brs));
  }

  static class RangeEncoder extends OneToOneEncoder implements LifeCycleAwareChannelHandler {

    private ChannelHandlerContext ctx;

    private final ByteRangeSet brs;

    private RangeEncoder(ByteRangeSet brs) {
      this.brs = brs;
    }

    @Override
    public void beforeAdd(ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
    }

    @Override
    public void afterAdd(ChannelHandlerContext ctx) throws Exception {
      // no-op
    }

    @Override
    public void beforeRemove(ChannelHandlerContext ctx) throws Exception {
      // no-op
    }

    @Override
    public void afterRemove(ChannelHandlerContext ctx) throws Exception {
      // no-op
    }

    @Override
    protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg)
        throws Exception {
      if (!(msg instanceof ChannelBuffer)) {
        return msg;
      }

      ChannelBuffer result = (ChannelBuffer) msg;

      ChannelBuffer raw = (ChannelBuffer) msg;
      byte[] in = new byte[raw.readableBytes()];
      raw.readBytes(in);

      RangeSpec spec = brs.get(0);

      byte[] out = Arrays.copyOfRange(in, spec.start, spec.length);

      result = ctx.getChannel().getConfig().getBufferFactory()
          .getBuffer(raw.order(), out, 0, spec.length);

      return result;
    }

  }
}
