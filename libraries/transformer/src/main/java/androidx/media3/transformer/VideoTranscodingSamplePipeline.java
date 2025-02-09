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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.SDK_INT;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.content.Context;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaFormat;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import java.util.List;
import org.checkerframework.dataflow.qual.Pure;

/**
 * Pipeline to decode video samples, apply transformations on the raw samples, and re-encode them.
 */
/* package */ final class VideoTranscodingSamplePipeline implements SamplePipeline {

  private final int outputRotationDegrees;
  private final DecoderInputBuffer decoderInputBuffer;
  private final Codec decoder;

  @Nullable private final FrameEditor frameEditor;

  private final Codec encoder;
  private final DecoderInputBuffer encoderOutputBuffer;

  private boolean waitingForFrameEditorInput;

  public VideoTranscodingSamplePipeline(
      Context context,
      Format inputFormat,
      TransformationRequest transformationRequest,
      Codec.DecoderFactory decoderFactory,
      Codec.EncoderFactory encoderFactory,
      List<String> allowedOutputMimeTypes,
      FallbackListener fallbackListener,
      Transformer.DebugViewProvider debugViewProvider)
      throws TransformationException {
    decoderInputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    // The decoder rotates encoded frames for display by inputFormat.rotationDegrees.
    int decodedWidth =
        (inputFormat.rotationDegrees % 180 == 0) ? inputFormat.width : inputFormat.height;
    int decodedHeight =
        (inputFormat.rotationDegrees % 180 == 0) ? inputFormat.height : inputFormat.width;
    float decodedAspectRatio = (float) decodedWidth / decodedHeight;

    Matrix transformationMatrix = new Matrix(transformationRequest.transformationMatrix);

    int outputWidth = decodedWidth;
    int outputHeight = decodedHeight;
    if (!transformationMatrix.isIdentity()) {
      // Scale frames by decodedAspectRatio, to account for FrameEditor's normalized device
      // coordinates (NDC) (a square from -1 to 1 for both x and y) and preserve rectangular display
      // of input pixels during transformations (ex. rotations). With scaling, transformationMatrix
      // operations operate on a rectangle for x from -decodedAspectRatio to decodedAspectRatio, and
      // y from -1 to 1.
      transformationMatrix.preScale(/* sx= */ decodedAspectRatio, /* sy= */ 1f);
      transformationMatrix.postScale(/* sx= */ 1f / decodedAspectRatio, /* sy= */ 1f);

      float[][] transformOnNdcPoints = {{-1, -1, 0, 1}, {-1, 1, 0, 1}, {1, -1, 0, 1}, {1, 1, 0, 1}};
      float xMin = Float.MAX_VALUE;
      float xMax = Float.MIN_VALUE;
      float yMin = Float.MAX_VALUE;
      float yMax = Float.MIN_VALUE;
      for (float[] transformOnNdcPoint : transformOnNdcPoints) {
        transformationMatrix.mapPoints(transformOnNdcPoint);
        xMin = min(xMin, transformOnNdcPoint[0]);
        xMax = max(xMax, transformOnNdcPoint[0]);
        yMin = min(yMin, transformOnNdcPoint[1]);
        yMax = max(yMax, transformOnNdcPoint[1]);
      }

      float xCenter = (xMax + xMin) / 2f;
      float yCenter = (yMax + yMin) / 2f;
      transformationMatrix.postTranslate(-xCenter, -yCenter);

      float ndcWidthAndHeight = 2f; // Length from -1 to 1.
      float xScale = (xMax - xMin) / ndcWidthAndHeight;
      float yScale = (yMax - yMin) / ndcWidthAndHeight;
      transformationMatrix.postScale(1f / xScale, 1f / yScale);
      outputWidth = Math.round(decodedWidth * xScale);
      outputHeight = Math.round(decodedHeight * yScale);
    }
    // Scale width and height to desired transformationRequest.outputHeight, preserving
    // aspect ratio.
    if (transformationRequest.outputHeight != C.LENGTH_UNSET
        && transformationRequest.outputHeight != outputHeight) {
      outputWidth =
          Math.round((float) transformationRequest.outputHeight * outputWidth / outputHeight);
      outputHeight = transformationRequest.outputHeight;
    }

    // Encoders commonly support higher maximum widths than maximum heights. Rotate the decoded
    // video before encoding, so the encoded video's width >= height, and set outputRotationDegrees
    // to ensure the video is displayed in the correct orientation.
    int requestedEncoderWidth;
    int requestedEncoderHeight;
    boolean swapEncodingDimensions = outputHeight > outputWidth;
    if (swapEncodingDimensions) {
      outputRotationDegrees = 90;
      requestedEncoderWidth = outputHeight;
      requestedEncoderHeight = outputWidth;
      // TODO(b/201293185): After fragment shader transformations are implemented, put
      // postRotate in a later vertex shader.
      transformationMatrix.postRotate(outputRotationDegrees);
    } else {
      outputRotationDegrees = 0;
      requestedEncoderWidth = outputWidth;
      requestedEncoderHeight = outputHeight;
    }

    Format requestedEncoderFormat =
        new Format.Builder()
            .setWidth(requestedEncoderWidth)
            .setHeight(requestedEncoderHeight)
            .setRotationDegrees(0)
            .setSampleMimeType(
                transformationRequest.videoMimeType != null
                    ? transformationRequest.videoMimeType
                    : inputFormat.sampleMimeType)
            .build();
    encoder = encoderFactory.createForVideoEncoding(requestedEncoderFormat, allowedOutputMimeTypes);
    Format encoderSupportedFormat = encoder.getConfigurationFormat();
    fallbackListener.onTransformationRequestFinalized(
        createFallbackTransformationRequest(
            transformationRequest,
            /* resolutionIsHeight= */ !swapEncodingDimensions,
            requestedEncoderFormat,
            encoderSupportedFormat));

    if (transformationRequest.enableHdrEditing
        || inputFormat.height != encoderSupportedFormat.height
        || inputFormat.width != encoderSupportedFormat.width
        || !transformationMatrix.isIdentity()) {
      frameEditor =
          FrameEditor.create(
              context,
              encoderSupportedFormat.width,
              encoderSupportedFormat.height,
              inputFormat.pixelWidthHeightRatio,
              transformationMatrix,
              /* outputSurface= */ encoder.getInputSurface(),
              transformationRequest.enableHdrEditing,
              debugViewProvider);
    } else {
      frameEditor = null;
    }

    decoder =
        decoderFactory.createForVideoDecoding(
            inputFormat,
            frameEditor == null ? encoder.getInputSurface() : frameEditor.getInputSurface());
  }

  @Override
  @Nullable
  public DecoderInputBuffer dequeueInputBuffer() throws TransformationException {
    return decoder.maybeDequeueInputBuffer(decoderInputBuffer) ? decoderInputBuffer : null;
  }

  @Override
  public void queueInputBuffer() throws TransformationException {
    decoder.queueInputBuffer(decoderInputBuffer);
  }

  @Override
  public boolean processData() throws TransformationException {
    if (hasProcessedAllInputData()) {
      return false;
    }

    if (SDK_INT >= 29) {
      return processDataV29();
    } else {
      return processDataDefault();
    }
  }

  /**
   * Processes input data from API 29.
   *
   * <p>In this method the decoder could decode multiple frames in one invocation; as compared to
   * {@link #processDataDefault()}, in which one frame is decoded in each invocation. Consequently,
   * if {@link FrameEditor} processes frames slower than the decoder, decoded frames are queued up
   * in the decoder's output surface.
   *
   * <p>Prior to API 29, decoders may drop frames to keep their output surface from growing out of
   * bound; while after API 29, the {@link MediaFormat#KEY_ALLOW_FRAME_DROP} key prevents frame
   * dropping even when the surface is full. As dropping random frames is not acceptable in {@code
   * Transformer}, using this method requires API level 29 or higher.
   */
  @RequiresApi(29)
  private boolean processDataV29() throws TransformationException {
    if (frameEditor != null) {
      // Processes as many frames as possible. FrameEditor's output surface will block when it's
      // full, so there will be no frame drop and the surface will not grow out of bound.
      while (frameEditor.canProcessData()) {
        frameEditor.processData();
      }
    }

    while (decoder.getOutputBufferInfo() != null) {
      if (frameEditor != null) {
        frameEditor.registerInputFrame();
      }
      decoder.releaseOutputBuffer(/* render= */ true);
    }
    if (decoder.isEnded()) {
      signalEndOfInputStream();
    }

    return frameEditor != null && frameEditor.canProcessData();
  }

  /** Processes input data. */
  private boolean processDataDefault() throws TransformationException {
    if (frameEditor != null) {
      if (frameEditor.canProcessData()) {
        waitingForFrameEditorInput = false;
        frameEditor.processData();
        return true;
      }
      if (waitingForFrameEditorInput) {
        return false;
      }
    }

    boolean decoderHasOutputBuffer = decoder.getOutputBufferInfo() != null;
    if (decoderHasOutputBuffer) {
      if (frameEditor != null) {
        frameEditor.registerInputFrame();
        waitingForFrameEditorInput = true;
      }
      decoder.releaseOutputBuffer(/* render= */ true);
    }
    if (decoder.isEnded()) {
      signalEndOfInputStream();
      return false;
    }
    return decoderHasOutputBuffer && !waitingForFrameEditorInput;
  }

  @Override
  @Nullable
  public Format getOutputFormat() throws TransformationException {
    @Nullable Format format = encoder.getOutputFormat();
    return format == null
        ? null
        : format.buildUpon().setRotationDegrees(outputRotationDegrees).build();
  }

  @Override
  @Nullable
  public DecoderInputBuffer getOutputBuffer() throws TransformationException {
    encoderOutputBuffer.data = encoder.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    MediaCodec.BufferInfo bufferInfo = checkNotNull(encoder.getOutputBufferInfo());
    encoderOutputBuffer.timeUs = bufferInfo.presentationTimeUs;
    encoderOutputBuffer.setFlags(bufferInfo.flags);
    return encoderOutputBuffer;
  }

  @Override
  public void releaseOutputBuffer() throws TransformationException {
    encoder.releaseOutputBuffer(/* render= */ false);
  }

  @Override
  public boolean isEnded() {
    return encoder.isEnded();
  }

  @Override
  public void release() {
    if (frameEditor != null) {
      frameEditor.release();
    }
    decoder.release();
    encoder.release();
  }

  @Pure
  private static TransformationRequest createFallbackTransformationRequest(
      TransformationRequest transformationRequest,
      boolean resolutionIsHeight,
      Format requestedFormat,
      Format actualFormat) {
    // TODO(b/210591626): Also update bitrate etc. once encoder configuration and fallback are
    // implemented.
    if (Util.areEqual(requestedFormat.sampleMimeType, actualFormat.sampleMimeType)
        && ((!resolutionIsHeight && requestedFormat.width == actualFormat.width)
            || (resolutionIsHeight && requestedFormat.height == actualFormat.height))) {
      return transformationRequest;
    }
    return transformationRequest
        .buildUpon()
        .setVideoMimeType(actualFormat.sampleMimeType)
        .setResolution(resolutionIsHeight ? requestedFormat.height : requestedFormat.width)
        .build();
  }

  private boolean hasProcessedAllInputData() {
    return decoder.isEnded() && (frameEditor == null || frameEditor.isEnded());
  }

  private void signalEndOfInputStream() throws TransformationException {
    if (frameEditor != null) {
      frameEditor.signalEndOfInputStream();
    }
    if (frameEditor == null || frameEditor.isEnded()) {
      encoder.signalEndOfInputStream();
    }
  }
}
