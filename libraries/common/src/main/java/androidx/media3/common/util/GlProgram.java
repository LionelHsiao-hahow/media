/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.Buffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a GLSL shader program.
 *
 * <p>After constructing a program, keep a reference for its lifetime and call {@link #delete()} (or
 * release the current GL context) when it's no longer needed.
 */
@UnstableApi
public final class GlProgram {

  // https://www.khronos.org/registry/OpenGL/extensions/EXT/EXT_YUV_target.txt
  private static final int GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT = 0x8BE7;
  /** The identifier of a compiled and linked GLSL shader program. */
  private final int programId;

  private final Attribute[] attributes;
  private final Uniform[] uniforms;
  private final Map<String, Attribute> attributeByName;
  private final Map<String, Uniform> uniformByName;

  /**
   * Compiles a GL shader program from vertex and fragment shader GLSL GLES20 code.
   *
   * @param context The {@link Context}.
   * @param vertexShaderFilePath The path to a vertex shader program.
   * @param fragmentShaderFilePath The path to a fragment shader program.
   * @throws IOException When failing to read shader files.
   */
  public GlProgram(Context context, String vertexShaderFilePath, String fragmentShaderFilePath)
      throws IOException {
    this(
        GlUtil.loadAsset(context, vertexShaderFilePath),
        GlUtil.loadAsset(context, fragmentShaderFilePath));
  }

  /**
   * Creates a GL shader program from vertex and fragment shader GLSL GLES20 code.
   *
   * <p>This involves slow steps, like compiling, linking, and switching the GL program, so do not
   * call this in fast rendering loops.
   *
   * @param vertexShaderGlsl The vertex shader program.
   * @param fragmentShaderGlsl The fragment shader program.
   */
  public GlProgram(String vertexShaderGlsl, String fragmentShaderGlsl) {
    programId = GLES20.glCreateProgram();
    GlUtil.checkGlError();

    // Add the vertex and fragment shaders.
    addShader(programId, GLES20.GL_VERTEX_SHADER, vertexShaderGlsl);
    addShader(programId, GLES20.GL_FRAGMENT_SHADER, fragmentShaderGlsl);

    // Link and use the program, and enumerate attributes/uniforms.
    GLES20.glLinkProgram(programId);
    int[] linkStatus = new int[] {GLES20.GL_FALSE};
    GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, /* offset= */ 0);
    if (linkStatus[0] != GLES20.GL_TRUE) {
      GlUtil.throwGlException(
          "Unable to link shader program: \n" + GLES20.glGetProgramInfoLog(programId));
    }
    GLES20.glUseProgram(programId);
    attributeByName = new HashMap<>();
    int[] attributeCount = new int[1];
    GLES20.glGetProgramiv(programId, GLES20.GL_ACTIVE_ATTRIBUTES, attributeCount, /* offset= */ 0);
    attributes = new Attribute[attributeCount[0]];
    for (int i = 0; i < attributeCount[0]; i++) {
      Attribute attribute = Attribute.create(programId, i);
      attributes[i] = attribute;
      attributeByName.put(attribute.name, attribute);
    }
    uniformByName = new HashMap<>();
    int[] uniformCount = new int[1];
    GLES20.glGetProgramiv(programId, GLES20.GL_ACTIVE_UNIFORMS, uniformCount, /* offset= */ 0);
    uniforms = new Uniform[uniformCount[0]];
    for (int i = 0; i < uniformCount[0]; i++) {
      Uniform uniform = Uniform.create(programId, i);
      uniforms[i] = uniform;
      uniformByName.put(uniform.name, uniform);
    }
    GlUtil.checkGlError();
  }

  private static void addShader(int programId, int type, String glsl) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, glsl);
    GLES20.glCompileShader(shader);

    int[] result = new int[] {GLES20.GL_FALSE};
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, /* offset= */ 0);
    if (result[0] != GLES20.GL_TRUE) {
      GlUtil.throwGlException(GLES20.glGetShaderInfoLog(shader) + ", source: " + glsl);
    }

    GLES20.glAttachShader(programId, shader);
    GLES20.glDeleteShader(shader);
    GlUtil.checkGlError();
  }

  private static int getAttributeLocation(int programId, String attributeName) {
    return GLES20.glGetAttribLocation(programId, attributeName);
  }

  /** Returns the location of an {@link Attribute}. */
  private int getAttributeLocation(String attributeName) {
    return getAttributeLocation(programId, attributeName);
  }

  private static int getUniformLocation(int programId, String uniformName) {
    return GLES20.glGetUniformLocation(programId, uniformName);
  }

  /** Returns the location of a {@link Uniform}. */
  public int getUniformLocation(String uniformName) {
    return getUniformLocation(programId, uniformName);
  }

  /**
   * Uses the program.
   *
   * <p>Call this in the rendering loop to switch between different programs.
   */
  public void use() {
    // TODO(b/214975934): When multiple GL programs are supported by Transformer, make sure
    // to call use() to switch between programs.
    GLES20.glUseProgram(programId);
    GlUtil.checkGlError();
  }

  /** Deletes the program. Deleted programs cannot be used again. */
  public void delete() {
    GLES20.glDeleteProgram(programId);
    GlUtil.checkGlError();
  }

  /**
   * Returns the location of an {@link Attribute}, which has been enabled as a vertex attribute
   * array.
   */
  public int getAttributeArrayLocationAndEnable(String attributeName) {
    int location = getAttributeLocation(attributeName);
    GLES20.glEnableVertexAttribArray(location);
    GlUtil.checkGlError();
    return location;
  }

  /** Sets a float buffer type attribute. */
  public void setBufferAttribute(String name, float[] values, int size) {
    checkNotNull(attributeByName.get(name)).setBuffer(values, size);
  }

  /** Sets a texture sampler type uniform. */
  public void setSamplerTexIdUniform(String name, int texId, int unit) {
    checkNotNull(uniformByName.get(name)).setSamplerTexId(texId, unit);
  }

  /** Sets a float type uniform. */
  public void setFloatUniform(String name, float value) {
    checkNotNull(uniformByName.get(name)).setFloat(value);
  }

  /** Sets a float array type uniform. */
  public void setFloatsUniform(String name, float[] value) {
    checkNotNull(uniformByName.get(name)).setFloats(value);
  }

  /** Binds all attributes and uniforms in the program. */
  public void bindAttributesAndUniforms() {
    for (Attribute attribute : attributes) {
      attribute.bind();
    }
    for (Uniform uniform : uniforms) {
      uniform.bind();
    }
  }

  /** Returns the length of the null-terminated C string in {@code cString}. */
  private static int getCStringLength(byte[] cString) {
    for (int i = 0; i < cString.length; ++i) {
      if (cString[i] == '\0') {
        return i;
      }
    }
    return cString.length;
  }

  /**
   * GL attribute, which can be attached to a buffer with {@link Attribute#setBuffer(float[], int)}.
   */
  private static final class Attribute {

    /* Returns the attribute at the given index in the program. */
    public static Attribute create(int programId, int index) {
      int[] length = new int[1];
      GLES20.glGetProgramiv(
          programId, GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, length, /* offset= */ 0);
      byte[] nameBytes = new byte[length[0]];

      GLES20.glGetActiveAttrib(
          programId,
          index,
          length[0],
          /* unusedLength */ new int[1],
          /* lengthOffset= */ 0,
          /* unusedSize */ new int[1],
          /* sizeOffset= */ 0,
          /* unusedType */ new int[1],
          /* typeOffset= */ 0,
          nameBytes,
          /* nameOffset= */ 0);
      String name = new String(nameBytes, /* offset= */ 0, getCStringLength(nameBytes));
      int location = getAttributeLocation(programId, name);

      return new Attribute(name, index, location);
    }

    /** The name of the attribute in the GLSL sources. */
    public final String name;

    private final int index;
    private final int location;

    @Nullable private Buffer buffer;
    private int size;

    private Attribute(String name, int index, int location) {
      this.name = name;
      this.index = index;
      this.location = location;
    }

    /**
     * Configures {@link #bind()} to attach vertices in {@code buffer} (each of size {@code size}
     * elements) to this {@link Attribute}.
     *
     * @param buffer Buffer to bind to this attribute.
     * @param size Number of elements per vertex.
     */
    public void setBuffer(float[] buffer, int size) {
      this.buffer = GlUtil.createBuffer(buffer);
      this.size = size;
    }

    /**
     * Sets the vertex attribute to whatever was attached via {@link #setBuffer(float[], int)}.
     *
     * <p>Should be called before each drawing call.
     */
    public void bind() {
      Buffer buffer = checkNotNull(this.buffer, "call setBuffer before bind");
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, /* buffer= */ 0);
      GLES20.glVertexAttribPointer(
          location, size, GLES20.GL_FLOAT, /* normalized= */ false, /* stride= */ 0, buffer);
      GLES20.glEnableVertexAttribArray(index);
      GlUtil.checkGlError();
    }
  }

  /**
   * GL uniform, which can be attached to a sampler using {@link Uniform#setSamplerTexId(int, int)}.
   */
  private static final class Uniform {

    /** Returns the uniform at the given index in the program. */
    public static Uniform create(int programId, int index) {
      int[] length = new int[1];
      GLES20.glGetProgramiv(
          programId, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, length, /* offset= */ 0);

      int[] type = new int[1];
      byte[] nameBytes = new byte[length[0]];

      GLES20.glGetActiveUniform(
          programId,
          index,
          length[0],
          /* unusedLength */ new int[1],
          /* lengthOffset= */ 0,
          /* unusedSize */ new int[1],
          /*sizeOffset= */ 0,
          type,
          /* typeOffset= */ 0,
          nameBytes,
          /* nameOffset= */ 0);
      String name = new String(nameBytes, /* offset= */ 0, getCStringLength(nameBytes));
      int location = getUniformLocation(programId, name);

      return new Uniform(name, location, type[0]);
    }

    /** The name of the uniform in the GLSL sources. */
    public final String name;

    private final int location;
    private final int type;
    private final float[] value;

    private int texId;
    private int unit;

    private Uniform(String name, int location, int type) {
      this.name = name;
      this.location = location;
      this.type = type;
      this.value = new float[16];
    }

    /**
     * Configures {@link #bind()} to use the specified {@code texId} for this sampler uniform.
     *
     * @param texId The GL texture identifier from which to sample.
     * @param unit The GL texture unit index.
     */
    public void setSamplerTexId(int texId, int unit) {
      this.texId = texId;
      this.unit = unit;
    }

    /** Configures {@link #bind()} to use the specified float {@code value} for this uniform. */
    public void setFloat(float value) {
      this.value[0] = value;
    }

    /** Configures {@link #bind()} to use the specified float[] {@code value} for this uniform. */
    public void setFloats(float[] value) {
      System.arraycopy(value, /* srcPos= */ 0, this.value, /* destPos= */ 0, value.length);
    }

    /**
     * Sets the uniform to whatever value was passed via {@link #setSamplerTexId(int, int)}, {@link
     * #setFloat(float)} or {@link #setFloats(float[])}.
     *
     * <p>Should be called before each drawing call.
     */
    public void bind() {
      if (type == GLES20.GL_FLOAT) {
        GLES20.glUniform1fv(location, /* count= */ 1, value, /* offset= */ 0);
        GlUtil.checkGlError();
        return;
      }

      if (type == GLES20.GL_FLOAT_MAT3) {
        GLES20.glUniformMatrix3fv(
            location, /* count= */ 1, /* transpose= */ false, value, /* offset= */ 0);
        GlUtil.checkGlError();
        return;
      }

      if (type == GLES20.GL_FLOAT_MAT4) {
        GLES20.glUniformMatrix4fv(
            location, /* count= */ 1, /* transpose= */ false, value, /* offset= */ 0);
        GlUtil.checkGlError();
        return;
      }

      if (texId == 0) {
        throw new IllegalStateException("No call to setSamplerTexId() before bind.");
      }
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
      if (type == GLES11Ext.GL_SAMPLER_EXTERNAL_OES || type == GL_SAMPLER_EXTERNAL_2D_Y2Y_EXT) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
      } else if (type == GLES20.GL_SAMPLER_2D) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
      } else {
        throw new IllegalStateException("Unexpected uniform type: " + type);
      }
      GLES20.glUniform1i(location, unit);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      GlUtil.checkGlError();
    }
  }
}
