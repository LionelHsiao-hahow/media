// Copyright 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Fragment shader that samples from an external texture with uTexSampler,
// copying from this texture to the current output.

#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexSampler;
varying vec2 vTexCoords;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexCoords);
}
