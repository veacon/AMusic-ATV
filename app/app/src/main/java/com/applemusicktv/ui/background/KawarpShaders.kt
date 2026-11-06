package com.applemusicktv.ui.background

/** Original shader sources from Better Lyrics / Kawarp (MIT); tuning is supplied by the renderer,
 * commit bb914faf44a394c8b05896b31b21edbb369b5596. See assets/licenses/Kawarp-MIT.txt.
 * The six reference shaders remain unchanged; PALETTE is our separate colour evolution pass.
 * This is Kawase blur plus domain warping, not a Navier–Stokes simulation. */
internal object KawarpShaders {
    val VERTEX = """
        attribute vec2 a_position;
          attribute vec2 a_texCoord;
          varying vec2 v_texCoord;
          void main() {
            gl_Position = vec4(a_position, 0.0, 1.0);
            v_texCoord = a_texCoord;
          }
    """.trimIndent()

    val BLUR = """
        precision highp float;
          uniform sampler2D u_texture;
          uniform vec2 u_resolution;
          uniform float u_offset;
          varying vec2 v_texCoord;
        
          void main() {
            highp vec2 texelSize = 1.0 / u_resolution;
            highp vec4 color = vec4(0.0);
        
            color += texture2D(u_texture, v_texCoord + vec2(-u_offset, -u_offset) * texelSize);
            color += texture2D(u_texture, v_texCoord + vec2(u_offset, -u_offset) * texelSize);
            color += texture2D(u_texture, v_texCoord + vec2(-u_offset, u_offset) * texelSize);
            color += texture2D(u_texture, v_texCoord + vec2(u_offset, u_offset) * texelSize);
        
            gl_FragColor = color * 0.25;
          }
    """.trimIndent()

    val BLEND = """
        precision highp float;
          uniform sampler2D u_texture1;
          uniform sampler2D u_texture2;
          uniform float u_blend;
          varying vec2 v_texCoord;
        
          void main() {
            vec4 color1 = texture2D(u_texture1, v_texCoord);
            vec4 color2 = texture2D(u_texture2, v_texCoord);
            gl_FragColor = mix(color1, color2, u_blend);
          }
    """.trimIndent()

    val TINT = """
        precision highp float;
          uniform sampler2D u_texture;
          uniform vec3 u_tintColor;
          uniform float u_tintIntensity;
          varying vec2 v_texCoord;
        
          void main() {
            vec4 color = texture2D(u_texture, v_texCoord);
            float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
        
            // darkMask: 1.0 for black, 0.0 for luma >= 0.5
            float darkMask = 1.0 - smoothstep(0.0, 0.5, luma);
        
            // Blend dark areas toward tint color
            color.rgb = mix(color.rgb, u_tintColor, darkMask * u_tintIntensity);
        
            gl_FragColor = color;
          }
    """.trimIndent()

    /** Reassign artwork colours slowly while leaving the warp geometry independent.
     * Samples stay inside the same blurred artwork; no hue wheel or unrelated palette. */
    val PALETTE = """
        precision highp float;
        uniform sampler2D u_texture;
        uniform float u_phase;
        varying vec2 v_texCoord;

        vec2 reflectUV(vec2 uv) {
            return 1.0 - abs(mod(uv, 2.0) - 1.0);
        }

        void main() {
            vec2 uv = v_texCoord;
            vec3 a = texture2D(u_texture, uv).rgb;
            vec3 b = texture2D(u_texture, reflectUV(1.0 - uv + vec2(0.16, -0.10))).rgb;
            vec3 c = texture2D(u_texture, reflectUV(vec2(uv.y, 1.0 - uv.x) + vec2(-0.18, 0.12))).rgb;
            // A small spatial delay prevents the whole screen fading colours in unison.
            float phase = fract(u_phase + 0.035 * sin(uv.x * 6.2831853 + uv.y * 3.1415927)) * 3.0;
            float part = fract(phase);
            float blend = 0.5 - 0.5 * cos(part * 3.1415927);
            vec3 colour;
            if (phase < 1.0) colour = mix(a, b, blend);
            else if (phase < 2.0) colour = mix(b, c, blend);
            else colour = mix(c, a, blend);
            gl_FragColor = vec4(colour, 1.0);
        }
    """.trimIndent()

    val WARP = """
        precision highp float;
          uniform sampler2D u_texture;
          uniform float u_time;
          uniform float u_intensity;
          varying vec2 v_texCoord;
        
          vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
          vec2 mod289(vec2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
          vec3 permute(vec3 x) { return mod289(((x*34.0)+1.0)*x); }
        
          float snoise(vec2 v) {
            const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                -0.577350269189626, 0.024390243902439);
            vec2 i  = floor(v + dot(v, C.yy));
            vec2 x0 = v - i + dot(i, C.xx);
            vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec4 x12 = x0.xyxy + C.xxzz;
            x12.xy -= i1;
            i = mod289(i);
            vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
            vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
            m = m*m; m = m*m;
            vec3 x = 2.0 * fract(p * C.www) - 1.0;
            vec3 h = abs(x) - 0.5;
            vec3 ox = floor(x + 0.5);
            vec3 a0 = x - ox;
            m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
            vec3 g;
            g.x = a0.x * x0.x + h.x * x0.y;
            g.yz = a0.yz * x12.xz + h.yz * x12.yw;
            return 130.0 * dot(m, g);
          }
        
          void main() {
            vec2 uv = v_texCoord;
            float t = u_time * 0.05;
        
            vec2 center = uv - 0.5;
            float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
        
            // Large-scale movement (slow, big blobs)
            float n1 = snoise(uv * 0.35 + vec2(t, t * 0.7));
            float n2 = snoise(uv * 0.35 + vec2(-t * 0.8, t * 0.5) + vec2(50.0, 50.0));
        
            // Medium-scale detail (adds organic movement)
            float n3 = snoise(uv * 0.9 + vec2(t * 1.2, -t) + vec2(100.0, 0.0));
            float n4 = snoise(uv * 0.9 + vec2(-t, t * 1.1) + vec2(0.0, 100.0));
        
            // Combine two octaves
            vec2 warp = vec2(
              n1 * 0.65 + n3 * 0.35,
              n2 * 0.65 + n4 * 0.35
            ) * centerWeight;
        
            vec2 warpedUV = uv + warp * u_intensity;
            warpedUV = clamp(warpedUV, 0.0, 1.0);
        
            gl_FragColor = texture2D(u_texture, warpedUV);
          }
    """.trimIndent()

    val OUTPUT = """
        precision highp float;
          uniform sampler2D u_texture;
          uniform float u_saturation;
          uniform float u_dithering;
          uniform float u_time;
          uniform float u_scale;
          uniform vec2 u_resolution;
          varying vec2 v_texCoord;
        
          highp float hash(highp vec3 p) {
            p = fract(p * 0.1031);
            p += dot(p, p.zyx + 31.32);
            return fract((p.x + p.y) * p.z);
          }
        
          void main() {
            vec2 uv = (v_texCoord - 0.5) / u_scale + 0.5;
            uv = clamp(uv, 0.0, 1.0);
        
            vec4 color = texture2D(u_texture, uv);
        
            vec2 center = v_texCoord - 0.5;
            float vignette = 1.0 - dot(center, center) * 0.3;
            color.rgb *= vignette;
        
            float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
            color.rgb = mix(vec3(gray), color.rgb, u_saturation);
        
            highp vec2 pixelPos = floor(v_texCoord * u_resolution);
            highp float noise = hash(vec3(pixelPos, floor(u_time * 60.0)));
            color.rgb += (noise - 0.5) * u_dithering;
        
            gl_FragColor = color;
          }
    """.trimIndent()

}
