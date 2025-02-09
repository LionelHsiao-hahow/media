/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import android.media.MediaCodec.BufferInfo;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Provides a layer of abstraction for interacting with decoders and encoders.
 *
 * <p>{@link DecoderInputBuffer DecoderInputBuffers} are used as both decoders' and encoders' input
 * buffers.
 */
@UnstableApi
public interface Codec {

  /** A factory for {@link Codec decoder} instances. */
  interface DecoderFactory {

    /** A default {@code DecoderFactory} implementation. */
    DecoderFactory DEFAULT = new DefaultDecoderFactory();

    /**
     * Returns a {@link Codec} for audio decoding.
     *
     * @param format The {@link Format} (of the input data) used to determine the underlying decoder
     *     and its configuration values.
     * @return A {@link Codec} for audio decoding.
     * @throws TransformationException If no suitable {@link Codec} can be created.
     */
    Codec createForAudioDecoding(Format format) throws TransformationException;

    /**
     * Returns a {@link Codec} for video decoding.
     *
     * @param format The {@link Format} (of the input data) used to determine the underlying decoder
     *     and its configuration values.
     * @param outputSurface The {@link Surface} to which the decoder output is rendered.
     * @return A {@link Codec} for video decoding.
     * @throws TransformationException If no suitable {@link Codec} can be created.
     */
    Codec createForVideoDecoding(Format format, Surface outputSurface)
        throws TransformationException;
  }

  /** A factory for {@link Codec encoder} instances. */
  interface EncoderFactory {

    /** A default {@code EncoderFactory} implementation. */
    EncoderFactory DEFAULT = new DefaultEncoderFactory();

    /**
     * Returns a {@link Codec} for audio encoding.
     *
     * <p>This method must validate that the {@link Codec} is configured to produce one of the
     * {@code allowedMimeTypes}. The {@link Format#sampleMimeType sample MIME type} given in {@code
     * format} is not necessarily allowed.
     *
     * @param format The {@link Format} (of the output data) used to determine the underlying
     *     encoder and its configuration values.
     * @param allowedMimeTypes The non-empty list of allowed output sample {@link MimeTypes MIME
     *     types}.
     * @return A {@link Codec} for audio encoding.
     * @throws TransformationException If no suitable {@link Codec} can be created.
     */
    Codec createForAudioEncoding(Format format, List<String> allowedMimeTypes)
        throws TransformationException;

    /**
     * Returns a {@link Codec} for video encoding.
     *
     * <p>This method must validate that the {@link Codec} is configured to produce one of the
     * {@code allowedMimeTypes}. The {@link Format#sampleMimeType sample MIME type} given in {@code
     * format} is not necessarily allowed.
     *
     * @param format The {@link Format} (of the output data) used to determine the underlying
     *     encoder and its configuration values. {@link Format#sampleMimeType}, {@link Format#width}
     *     and {@link Format#height} must be set to those of the desired output video format. {@link
     *     Format#rotationDegrees} should be 0. The video should always be in landscape orientation.
     * @param allowedMimeTypes The non-empty list of allowed output sample {@link MimeTypes MIME
     *     types}.
     * @return A {@link Codec} for video encoding.
     * @throws TransformationException If no suitable {@link Codec} can be created.
     */
    Codec createForVideoEncoding(Format format, List<String> allowedMimeTypes)
        throws TransformationException;
  }

  /**
   * Returns the {@link Format} used for configuring the {@code Codec}.
   *
   * <p>The configuration {@link Format} is the input {@link Format} used by the {@link
   * DecoderFactory} or output {@link Format} used by the {@link EncoderFactory} for selecting and
   * configuring the underlying decoder or encoder.
   */
  Format getConfigurationFormat();

  /**
   * Returns the input {@link Surface} of an underlying video encoder.
   *
   * <p>This method must only be called on video encoders because audio/video decoders and audio
   * encoders don't use a {@link Surface} as input.
   */
  Surface getInputSurface();

  /**
   * Dequeues a writable input buffer, if available.
   *
   * <p>This method must not be called from video encoders because they must use {@link Surface
   * surfaces} as inputs.
   *
   * @param inputBuffer The buffer where the dequeued buffer data is stored, at {@link
   *     DecoderInputBuffer#data inputBuffer.data}.
   * @return Whether an input buffer is ready to be used.
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) throws TransformationException;

  /**
   * Queues an input buffer to the {@code Codec}. No buffers may be queued after {@link
   * DecoderInputBuffer#isEndOfStream() end of stream} buffer has been queued.
   *
   * <p>This method must not be called from video encoders because they must use {@link Surface
   * surfaces} as inputs.
   *
   * @param inputBuffer The {@link DecoderInputBuffer input buffer}.
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  void queueInputBuffer(DecoderInputBuffer inputBuffer) throws TransformationException;

  /**
   * Signals end-of-stream on input to a video encoder.
   *
   * <p>This method must only be called on video encoders because they must use a {@link Surface} as
   * input. For audio/video decoders or audio encoders, the {@link C#BUFFER_FLAG_END_OF_STREAM} flag
   * should be set on the last input buffer {@link #queueInputBuffer(DecoderInputBuffer) queued}.
   *
   * @throws TransformationException If the underlying video encoder encounters a problem.
   */
  void signalEndOfInputStream() throws TransformationException;

  /**
   * Returns the current output format, or {@code null} if unavailable.
   *
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  @Nullable
  Format getOutputFormat() throws TransformationException;

  /**
   * Returns the current output {@link ByteBuffer}, or {@code null} if unavailable.
   *
   * <p>This method must not be called on video decoders because they must output to a {@link
   * Surface}.
   *
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  @Nullable
  ByteBuffer getOutputBuffer() throws TransformationException;

  /**
   * Returns the {@link BufferInfo} associated with the current output buffer, or {@code null} if
   * there is no output buffer available.
   *
   * <p>This method returns {@code null} if and only if {@link #getOutputBuffer()} returns null.
   *
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  @Nullable
  BufferInfo getOutputBufferInfo() throws TransformationException;

  /**
   * Releases the current output buffer.
   *
   * <p>Only set {@code render} to {@code true} when the {@code Codec} is a video decoder. Setting
   * {@code render} to {@code true} will first render the buffer to the output surface. In this
   * case, the surface will release the buffer back to the {@code Codec} once it is no longer
   * used/displayed.
   *
   * <p>This should be called after the buffer has been processed. The next output buffer will not
   * be available until the current output buffer has been released.
   *
   * @param render Whether the buffer needs to be rendered to the output {@link Surface}.
   * @throws TransformationException If the underlying decoder or encoder encounters a problem.
   */
  void releaseOutputBuffer(boolean render) throws TransformationException;

  /**
   * Returns whether the {@code Codec}'s output stream has ended, and no more data can be dequeued.
   */
  boolean isEnded();

  /** Releases the {@code Codec}. */
  void release();
}
