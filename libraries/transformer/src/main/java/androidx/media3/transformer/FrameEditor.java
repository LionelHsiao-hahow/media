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
import static androidx.media3.common.util.Assertions.checkState;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.GlUtil;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code FrameEditor} applies changes to individual video frames.
 *
 * <p>Input becomes available on its {@link #getInputSurface() input surface} asynchronously so
 * {@link #canProcessData()} needs to be checked before calling {@link #processData()}. Output is
 * written to its {@link #create(Context, int, int, float, Matrix, Surface, boolean,
 * Transformer.DebugViewProvider) output surface}.
 */
/* package */ final class FrameEditor {

  static {
    GlUtil.glAssertionsEnabled = true;
  }

  /**
   * Returns a new {@code FrameEditor} for applying changes to individual frames.
   *
   * @param context A {@link Context}.
   * @param outputWidth The output width in pixels.
   * @param outputHeight The output height in pixels.
   * @param pixelWidthHeightRatio The ratio of width over height, for each pixel.
   * @param transformationMatrix The transformation matrix to apply to each frame.
   * @param outputSurface The {@link Surface}.
   * @param enableExperimentalHdrEditing Whether to attempt to process the input as an HDR signal.
   * @param debugViewProvider Provider for optional debug views to show intermediate output.
   * @return A configured {@code FrameEditor}.
   * @throws TransformationException If the {@code pixelWidthHeightRatio} isn't 1, reading shader
   *     files fails, or an OpenGL error occurs while creating and configuring the OpenGL
   *     components.
   */
  public static FrameEditor create(
      Context context,
      int outputWidth,
      int outputHeight,
      float pixelWidthHeightRatio,
      Matrix transformationMatrix,
      Surface outputSurface,
      boolean enableExperimentalHdrEditing,
      Transformer.DebugViewProvider debugViewProvider)
      throws TransformationException {
    if (pixelWidthHeightRatio != 1.0f) {
      // TODO(b/211782176): Consider implementing support for non-square pixels.
      throw TransformationException.createForFrameEditor(
          new UnsupportedOperationException(
              "Transformer's frame editor currently does not support frame edits on non-square"
                  + " pixels. The pixelWidthHeightRatio is: "
                  + pixelWidthHeightRatio),
          TransformationException.ERROR_CODE_GL_INIT_FAILED);
    }

    GlFrameProcessor frameProcessor =
        new GlFrameProcessor(context, transformationMatrix, enableExperimentalHdrEditing);
    @Nullable
    SurfaceView debugSurfaceView =
        debugViewProvider.getDebugPreviewSurfaceView(outputWidth, outputHeight);

    EGLDisplay eglDisplay;
    EGLContext eglContext;
    EGLSurface eglSurface;
    int textureId;
    @Nullable EGLSurface debugPreviewEglSurface = null;
    try {
      eglDisplay = GlUtil.createEglDisplay();

      if (enableExperimentalHdrEditing) {
        eglContext = GlUtil.createEglContextEs3Rgba1010102(eglDisplay);
        // TODO(b/209404935): Don't assume BT.2020 PQ input/output.
        eglSurface = GlUtil.getEglSurfaceBt2020Pq(eglDisplay, outputSurface);
        if (debugSurfaceView != null) {
          debugPreviewEglSurface =
              GlUtil.getEglSurfaceBt2020Pq(eglDisplay, checkNotNull(debugSurfaceView.getHolder()));
        }
      } else {
        eglContext = GlUtil.createEglContext(eglDisplay);
        eglSurface = GlUtil.getEglSurface(eglDisplay, outputSurface);
        if (debugSurfaceView != null) {
          debugPreviewEglSurface =
              GlUtil.getEglSurface(eglDisplay, checkNotNull(debugSurfaceView.getHolder()));
        }
      }

      GlUtil.focusEglSurface(eglDisplay, eglContext, eglSurface, outputWidth, outputHeight);
      textureId = GlUtil.createExternalTexture();
      frameProcessor.initialize();
    } catch (IOException | GlUtil.GlException e) {
      throw TransformationException.createForFrameEditor(
          e, TransformationException.ERROR_CODE_GL_INIT_FAILED);
    }

    int debugPreviewWidth;
    int debugPreviewHeight;
    if (debugSurfaceView != null) {
      debugPreviewWidth = debugSurfaceView.getWidth();
      debugPreviewHeight = debugSurfaceView.getHeight();
    } else {
      debugPreviewWidth = C.LENGTH_UNSET;
      debugPreviewHeight = C.LENGTH_UNSET;
    }

    return new FrameEditor(
        eglDisplay,
        eglContext,
        eglSurface,
        textureId,
        frameProcessor,
        outputWidth,
        outputHeight,
        debugPreviewEglSurface,
        debugPreviewWidth,
        debugPreviewHeight);
  }

  private final GlFrameProcessor frameProcessor;
  private final float[] textureTransformMatrix;
  private final EGLDisplay eglDisplay;
  private final EGLContext eglContext;
  private final EGLSurface eglSurface;
  private final int textureId;
  private final AtomicInteger pendingInputFrameCount;
  private final AtomicInteger availableInputFrameCount;
  private final SurfaceTexture inputSurfaceTexture;
  private final Surface inputSurface;
  private final int outputWidth;
  private final int outputHeight;
  @Nullable private final EGLSurface debugPreviewEglSurface;
  private final int debugPreviewWidth;
  private final int debugPreviewHeight;

  private boolean inputStreamEnded;

  private FrameEditor(
      EGLDisplay eglDisplay,
      EGLContext eglContext,
      EGLSurface eglSurface,
      int textureId,
      GlFrameProcessor frameProcessor,
      int outputWidth,
      int outputHeight,
      @Nullable EGLSurface debugPreviewEglSurface,
      int debugPreviewWidth,
      int debugPreviewHeight) {
    this.eglDisplay = eglDisplay;
    this.eglContext = eglContext;
    this.eglSurface = eglSurface;
    this.textureId = textureId;
    this.frameProcessor = frameProcessor;
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;
    this.debugPreviewEglSurface = debugPreviewEglSurface;
    this.debugPreviewWidth = debugPreviewWidth;
    this.debugPreviewHeight = debugPreviewHeight;
    pendingInputFrameCount = new AtomicInteger();
    availableInputFrameCount = new AtomicInteger();
    textureTransformMatrix = new float[16];
    inputSurfaceTexture = new SurfaceTexture(textureId);
    inputSurfaceTexture.setOnFrameAvailableListener(
        surfaceTexture -> {
          checkState(pendingInputFrameCount.getAndDecrement() > 0);
          availableInputFrameCount.incrementAndGet();
        });
    inputSurface = new Surface(inputSurfaceTexture);
  }

  /** Returns the input {@link Surface}. */
  public Surface getInputSurface() {
    return inputSurface;
  }

  /**
   * Informs the frame editor that a frame will be queued to its input surface.
   *
   * <p>Should be called before rendering a frame to the frame editor's input surface.
   *
   * @throws IllegalStateException If called after {@link #signalEndOfInputStream()}.
   */
  public void registerInputFrame() {
    checkState(!inputStreamEnded);
    pendingInputFrameCount.incrementAndGet();
  }

  /**
   * Returns whether there is available input data that can be processed by calling {@link
   * #processData()}.
   */
  public boolean canProcessData() {
    return availableInputFrameCount.get() > 0;
  }

  /**
   * Processes an input frame.
   *
   * @throws TransformationException If an OpenGL error occurs while processing the data.
   * @throws IllegalStateException If there is no input data to process. Use {@link
   *     #canProcessData()} to check whether input data is available.
   */
  public void processData() throws TransformationException {
    checkState(canProcessData());
    try {
      inputSurfaceTexture.updateTexImage();
      inputSurfaceTexture.getTransformMatrix(textureTransformMatrix);
      GlUtil.focusEglSurface(eglDisplay, eglContext, eglSurface, outputWidth, outputHeight);
      frameProcessor.setTextureTransformMatrix(textureTransformMatrix);
      frameProcessor.updateProgramAndDraw(textureId);
      long presentationTimeNs = inputSurfaceTexture.getTimestamp();
      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
      EGL14.eglSwapBuffers(eglDisplay, eglSurface);

      if (debugPreviewEglSurface != null) {
        GlUtil.focusEglSurface(
            eglDisplay, eglContext, debugPreviewEglSurface, debugPreviewWidth, debugPreviewHeight);
        GLES20.glClearColor(/* red= */ 0, /* green= */ 0, /* blue= */ 0, /* alpha= */ 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // The four-vertex triangle strip forms a quad.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
        EGL14.eglSwapBuffers(eglDisplay, debugPreviewEglSurface);
      }
    } catch (GlUtil.GlException e) {
      throw TransformationException.createForFrameEditor(
          e, TransformationException.ERROR_CODE_GL_PROCESSING_FAILED);
    }
    availableInputFrameCount.decrementAndGet();
  }

  /** Releases all resources. */
  public void release() {
    frameProcessor.release();
    GlUtil.deleteTexture(textureId);
    GlUtil.destroyEglContext(eglDisplay, eglContext);
    inputSurfaceTexture.release();
    inputSurface.release();
  }

  /** Returns whether all data has been processed. */
  public boolean isEnded() {
    return inputStreamEnded
        && pendingInputFrameCount.get() == 0
        && availableInputFrameCount.get() == 0;
  }

  /** Informs the {@code FrameEditor} that no further input data should be accepted. */
  public void signalEndOfInputStream() {
    inputStreamEnded = true;
  }
}
