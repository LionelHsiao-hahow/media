/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.common.util;

import static android.opengl.GLU.gluErrorString;

import android.content.Context;
import android.content.pm.PackageManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGL10;

/** OpenGL ES utilities. */
@SuppressWarnings("InlinedApi") // GLES constants are used safely based on the API version.
@UnstableApi
public final class GlUtil {

  /** Thrown when an OpenGL error occurs and {@link #glAssertionsEnabled} is {@code true}. */
  public static final class GlException extends RuntimeException {
    /** Creates an instance with the specified error message. */
    public GlException(String message) {
      super(message);
    }
  }

  /** Whether to throw a {@link GlException} in case of an OpenGL error. */
  public static boolean glAssertionsEnabled = false;

  /** Number of vertices in a rectangle. */
  public static final int RECTANGLE_VERTICES_COUNT = 4;

  private static final String TAG = "GlUtil";

  // https://www.khronos.org/registry/EGL/extensions/EXT/EGL_EXT_protected_content.txt
  private static final String EXTENSION_PROTECTED_CONTENT = "EGL_EXT_protected_content";
  // https://www.khronos.org/registry/EGL/extensions/KHR/EGL_KHR_surfaceless_context.txt
  private static final String EXTENSION_SURFACELESS_CONTEXT = "EGL_KHR_surfaceless_context";

  // https://www.khronos.org/registry/EGL/extensions/KHR/EGL_KHR_gl_colorspace.txt
  private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
  // https://www.khronos.org/registry/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt
  private static final int EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340;

  private static final int[] EGL_WINDOW_SURFACE_ATTRIBUTES_NONE = new int[] {EGL14.EGL_NONE};
  private static final int[] EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ =
      new int[] {EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE};
  private static final int[] EGL_CONFIG_ATTRIBUTES_RGBA_8888 =
      new int[] {
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, /* redSize= */ 8,
        EGL14.EGL_GREEN_SIZE, /* greenSize= */ 8,
        EGL14.EGL_BLUE_SIZE, /* blueSize= */ 8,
        EGL14.EGL_ALPHA_SIZE, /* alphaSize= */ 8,
        EGL14.EGL_DEPTH_SIZE, /* depthSize= */ 0,
        EGL14.EGL_STENCIL_SIZE, /* stencilSize= */ 0,
        EGL14.EGL_NONE
      };
  private static final int[] EGL_CONFIG_ATTRIBUTES_RGBA_1010102 =
      new int[] {
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE, /* redSize= */ 10,
        EGL14.EGL_GREEN_SIZE, /* greenSize= */ 10,
        EGL14.EGL_BLUE_SIZE, /* blueSize= */ 10,
        EGL14.EGL_ALPHA_SIZE, /* alphaSize= */ 2,
        EGL14.EGL_DEPTH_SIZE, /* depthSize= */ 0,
        EGL14.EGL_STENCIL_SIZE, /* stencilSize= */ 0,
        EGL14.EGL_NONE
      };

  /** Class only contains static methods. */
  private GlUtil() {}

  /** Bounds of normalized device coordinates, commonly used for defining viewport boundaries. */
  public static float[] getNormalizedCoordinateBounds() {
    return new float[] {
      -1, -1, 0, 1,
      1, -1, 0, 1,
      -1, 1, 0, 1,
      1, 1, 0, 1
    };
  }

  /** Typical bounds used for sampling from textures. */
  public static float[] getTextureCoordinateBounds() {
    return new float[] {
      0, 0, 0, 1,
      1, 0, 0, 1,
      0, 1, 0, 1,
      1, 1, 0, 1
    };
  }

  /**
   * Returns whether creating a GL context with {@value #EXTENSION_PROTECTED_CONTENT} is possible.
   * If {@code true}, the device supports a protected output path for DRM content when using GL.
   */
  public static boolean isProtectedContentExtensionSupported(Context context) {
    if (Util.SDK_INT < 24) {
      return false;
    }
    if (Util.SDK_INT < 26 && ("samsung".equals(Util.MANUFACTURER) || "XT1650".equals(Util.MODEL))) {
      // Samsung devices running Nougat are known to be broken. See
      // https://github.com/google/ExoPlayer/issues/3373 and [Internal: b/37197802].
      // Moto Z XT1650 is also affected. See
      // https://github.com/google/ExoPlayer/issues/3215.
      return false;
    }
    if (Util.SDK_INT < 26
        && !context
            .getPackageManager()
            .hasSystemFeature(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)) {
      // Pre API level 26 devices were not well tested unless they supported VR mode.
      return false;
    }

    EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    @Nullable String eglExtensions = EGL14.eglQueryString(display, EGL10.EGL_EXTENSIONS);
    return eglExtensions != null && eglExtensions.contains(EXTENSION_PROTECTED_CONTENT);
  }

  /**
   * Returns whether creating a GL context with {@value #EXTENSION_SURFACELESS_CONTEXT} is possible.
   */
  public static boolean isSurfacelessContextExtensionSupported() {
    if (Util.SDK_INT < 17) {
      return false;
    }
    EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    @Nullable String eglExtensions = EGL14.eglQueryString(display, EGL10.EGL_EXTENSIONS);
    return eglExtensions != null && eglExtensions.contains(EXTENSION_SURFACELESS_CONTEXT);
  }

  /** Returns an initialized default {@link EGLDisplay}. */
  @RequiresApi(17)
  public static EGLDisplay createEglDisplay() {
    return Api17.createEglDisplay();
  }

  /** Returns a new {@link EGLContext} for the specified {@link EGLDisplay}. */
  @RequiresApi(17)
  public static EGLContext createEglContext(EGLDisplay eglDisplay) {
    return Api17.createEglContext(eglDisplay, /* version= */ 2, EGL_CONFIG_ATTRIBUTES_RGBA_8888);
  }

  /**
   * Returns a new {@link EGLContext} for the specified {@link EGLDisplay}, requesting ES 3 and an
   * RGBA 1010102 config.
   */
  @RequiresApi(17)
  public static EGLContext createEglContextEs3Rgba1010102(EGLDisplay eglDisplay) {
    return Api17.createEglContext(eglDisplay, /* version= */ 3, EGL_CONFIG_ATTRIBUTES_RGBA_1010102);
  }

  /**
   * Returns a new {@link EGLSurface} wrapping the specified {@code surface}.
   *
   * @param eglDisplay The {@link EGLDisplay} to attach the surface to.
   * @param surface The surface to wrap; must be a surface, surface texture or surface holder.
   */
  @RequiresApi(17)
  public static EGLSurface getEglSurface(EGLDisplay eglDisplay, Object surface) {
    return Api17.getEglSurface(
        eglDisplay, surface, EGL_CONFIG_ATTRIBUTES_RGBA_8888, EGL_WINDOW_SURFACE_ATTRIBUTES_NONE);
  }

  /**
   * Returns a new {@link EGLSurface} wrapping the specified {@code surface}, for HDR rendering with
   * Rec. 2020 color primaries and using the PQ transfer function.
   *
   * @param eglDisplay The {@link EGLDisplay} to attach the surface to.
   * @param surface The surface to wrap; must be a surface, surface texture or surface holder.
   */
  @RequiresApi(17)
  public static EGLSurface getEglSurfaceBt2020Pq(EGLDisplay eglDisplay, Object surface) {
    return Api17.getEglSurface(
        eglDisplay,
        surface,
        EGL_CONFIG_ATTRIBUTES_RGBA_1010102,
        EGL_WINDOW_SURFACE_ATTRIBUTES_BT2020_PQ);
  }

  /**
   * If there is an OpenGl error, logs the error and if {@link #glAssertionsEnabled} is true throws
   * a {@link GlException}.
   */
  public static void checkGlError() {
    int lastError = GLES20.GL_NO_ERROR;
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, "glError: " + gluErrorString(error));
      lastError = error;
    }
    if (lastError != GLES20.GL_NO_ERROR) {
      throwGlException("glError: " + gluErrorString(lastError));
    }
  }

  /**
   * Makes the specified {@code eglSurface} the render target, using a viewport of {@code width} by
   * {@code height} pixels.
   */
  @RequiresApi(17)
  public static void focusEglSurface(
      EGLDisplay eglDisplay, EGLContext eglContext, EGLSurface eglSurface, int width, int height) {
    Api17.focusRenderTarget(
        eglDisplay, eglContext, eglSurface, /* framebuffer= */ 0, width, height);
  }

  /**
   * Makes the specified {@code framebuffer} the render target, using a viewport of {@code width} by
   * {@code height} pixels.
   */
  @RequiresApi(17)
  public static void focusFramebuffer(
      EGLDisplay eglDisplay,
      EGLContext eglContext,
      EGLSurface eglSurface,
      int framebuffer,
      int width,
      int height) {
    Api17.focusRenderTarget(eglDisplay, eglContext, eglSurface, framebuffer, width, height);
  }

  /**
   * Deletes a GL texture.
   *
   * @param textureId The ID of the texture to delete.
   */
  public static void deleteTexture(int textureId) {
    GLES20.glDeleteTextures(/* n= */ 1, new int[] {textureId}, /* offset= */ 0);
    checkGlError();
  }

  /**
   * Destroys the {@link EGLContext} identified by the provided {@link EGLDisplay} and {@link
   * EGLContext}.
   */
  @RequiresApi(17)
  public static void destroyEglContext(
      @Nullable EGLDisplay eglDisplay, @Nullable EGLContext eglContext) {
    Api17.destroyEglContext(eglDisplay, eglContext);
  }

  /**
   * Allocates a FloatBuffer with the given data.
   *
   * @param data Used to initialize the new buffer.
   */
  public static FloatBuffer createBuffer(float[] data) {
    return (FloatBuffer) createBuffer(data.length).put(data).flip();
  }

  /**
   * Allocates a FloatBuffer.
   *
   * @param capacity The new buffer's capacity, in floats.
   */
  public static FloatBuffer createBuffer(int capacity) {
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(capacity * C.BYTES_PER_FLOAT);
    return byteBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
  }

  /**
   * Loads a file from the assets folder.
   *
   * @param context The {@link Context}.
   * @param assetPath The path to the file to load, from the assets folder.
   * @return The content of the file to load.
   * @throws IOException If the file couldn't be read.
   */
  public static String loadAsset(Context context, String assetPath) throws IOException {
    @Nullable InputStream inputStream = null;
    try {
      inputStream = context.getAssets().open(assetPath);
      return Util.fromUtf8Bytes(Util.toByteArray(inputStream));
    } finally {
      Util.closeQuietly(inputStream);
    }
  }

  /**
   * Creates a GL_TEXTURE_EXTERNAL_OES with default configuration of GL_LINEAR filtering and
   * GL_CLAMP_TO_EDGE wrapping.
   */
  public static int createExternalTexture() {
    return generateAndBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
  }

  /**
   * Returns the texture identifier for a newly-allocated texture with the specified dimensions.
   *
   * @param width of the new texture in pixels
   * @param height of the new texture in pixels
   */
  public static int createTexture(int width, int height) {
    int texId = generateAndBindTexture(GLES20.GL_TEXTURE_2D);
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(width * height * 4);
    GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D,
        /* level= */ 0,
        GLES20.GL_RGBA,
        width,
        height,
        /* border= */ 0,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        byteBuffer);
    checkGlError();
    return texId;
  }

  /**
   * Returns a GL texture identifier of a newly generated and bound texture of the requested type
   * with default configuration of GL_LINEAR filtering and GL_CLAMP_TO_EDGE wrapping.
   *
   * @param textureTarget The target to which the texture is bound, e.g. {@link
   *     GLES20#GL_TEXTURE_2D} for a two-dimensional texture or {@link
   *     GLES11Ext#GL_TEXTURE_EXTERNAL_OES} for an external texture.
   */
  private static int generateAndBindTexture(int textureTarget) {
    int[] texId = new int[1];
    GLES20.glGenTextures(/* n= */ 1, texId, /* offset= */ 0);
    checkGlError();
    GLES20.glBindTexture(textureTarget, texId[0]);
    checkGlError();
    GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    checkGlError();
    GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    checkGlError();
    GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    checkGlError();
    return texId[0];
  }

  /**
   * Returns a new framebuffer for the texture.
   *
   * @param texId The identifier of the texture to attach to the framebuffer.
   */
  public static int createFboForTexture(int texId) {
    int[] fboId = new int[1];
    GLES20.glGenFramebuffers(/* n= */ 1, fboId, /* offset= */ 0);
    checkGlError();
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
    checkGlError();
    GLES20.glFramebufferTexture2D(
        GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0);
    checkGlError();
    return fboId[0];
  }

  /* package */ static void throwGlException(String errorMsg) {
    Log.e(TAG, errorMsg);
    if (glAssertionsEnabled) {
      throw new GlException(errorMsg);
    }
  }

  private static void checkEglException(boolean expression, String errorMessage) {
    if (!expression) {
      throwGlException(errorMessage);
    }
  }

  @RequiresApi(17)
  private static final class Api17 {
    private Api17() {}

    @DoNotInline
    public static EGLDisplay createEglDisplay() {
      EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      checkEglException(!eglDisplay.equals(EGL14.EGL_NO_DISPLAY), "No EGL display.");
      if (!EGL14.eglInitialize(
          eglDisplay,
          /* unusedMajor */ new int[1],
          /* majorOffset= */ 0,
          /* unusedMinor */ new int[1],
          /* minorOffset= */ 0)) {
        throwGlException("Error in eglInitialize.");
      }
      checkGlError();
      return eglDisplay;
    }

    @DoNotInline
    public static EGLContext createEglContext(
        EGLDisplay eglDisplay, int version, int[] configAttributes) {
      int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE};
      EGLContext eglContext =
          EGL14.eglCreateContext(
              eglDisplay,
              getEglConfig(eglDisplay, configAttributes),
              EGL14.EGL_NO_CONTEXT,
              contextAttributes,
              /* offset= */ 0);
      if (eglContext == null) {
        EGL14.eglTerminate(eglDisplay);
        throwGlException(
            "eglCreateContext() failed to create a valid context. The device may not support EGL"
                + " version "
                + version);
      }
      checkGlError();
      return eglContext;
    }

    @DoNotInline
    public static EGLSurface getEglSurface(
        EGLDisplay eglDisplay,
        Object surface,
        int[] configAttributes,
        int[] windowSurfaceAttributes) {
      return EGL14.eglCreateWindowSurface(
          eglDisplay,
          getEglConfig(eglDisplay, configAttributes),
          surface,
          windowSurfaceAttributes,
          /* offset= */ 0);
    }

    @DoNotInline
    public static void focusRenderTarget(
        EGLDisplay eglDisplay,
        EGLContext eglContext,
        EGLSurface eglSurface,
        int framebuffer,
        int width,
        int height) {
      int[] boundFramebuffer = new int[1];
      GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFramebuffer, /* offset= */ 0);
      if (boundFramebuffer[0] != framebuffer) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
      }
      EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
      GLES20.glViewport(/* x= */ 0, /* y= */ 0, width, height);
    }

    @DoNotInline
    public static void destroyEglContext(
        @Nullable EGLDisplay eglDisplay, @Nullable EGLContext eglContext) {
      if (eglDisplay == null) {
        return;
      }
      EGL14.eglMakeCurrent(
          eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      int error = EGL14.eglGetError();
      checkEglException(error == EGL14.EGL_SUCCESS, "Error releasing context: " + error);
      if (eglContext != null) {
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        error = EGL14.eglGetError();
        checkEglException(error == EGL14.EGL_SUCCESS, "Error destroying context: " + error);
      }
      EGL14.eglReleaseThread();
      error = EGL14.eglGetError();
      checkEglException(error == EGL14.EGL_SUCCESS, "Error releasing thread: " + error);
      EGL14.eglTerminate(eglDisplay);
      error = EGL14.eglGetError();
      checkEglException(error == EGL14.EGL_SUCCESS, "Error terminating display: " + error);
    }

    @DoNotInline
    private static EGLConfig getEglConfig(EGLDisplay eglDisplay, int[] attributes) {
      EGLConfig[] eglConfigs = new EGLConfig[1];
      if (!EGL14.eglChooseConfig(
          eglDisplay,
          attributes,
          /* attrib_listOffset= */ 0,
          eglConfigs,
          /* configsOffset= */ 0,
          /* config_size= */ 1,
          /* unusedNumConfig */ new int[1],
          /* num_configOffset= */ 0)) {
        throwGlException("eglChooseConfig failed.");
      }
      return eglConfigs[0];
    }
  }
}
