package com.applemusicktv.ui.background

/** Independent GLSL port, shared equations with the approved desktop demo. */
internal object MacShaders {
    val VERTEX = """
attribute vec2 a_position;attribute vec2 a_texCoord;varying highp vec2 v_uv;
void main(){gl_Position=vec4(a_position,0.,1.);v_uv=a_texCoord;}
    """.trimIndent()
    val ROTATION_VERTEX = """
attribute vec2 a_position;attribute vec2 a_texCoord;
uniform float u_angle;uniform vec2 u_offset,u_aspect;varying highp vec2 v_uv;
vec2 rotateCW(vec2 p,float t){float c=cos(t),s=sin(t);return vec2(c*p.x+s*p.y,-s*p.x+c*p.y);}
void main(){float t=u_angle;
vec2 p=rotateCW(rotateCW(a_position,t)+u_offset,t)*u_aspect;
gl_Position=vec4(p,0.,1.);v_uv=a_texCoord;}
    """.trimIndent()
    val ROTATION = """
precision highp float;uniform sampler2D u_texture,u_old;uniform float u_mix;
varying highp vec2 v_uv;void main(){vec3 c=mix(texture2D(u_old,v_uv).rgb,texture2D(u_texture,v_uv).rgb,u_mix);
float g=dot(c,vec3(.3,.59,.11));gl_FragColor=vec4(vec3(g)+(c-g)*1.3,1.);}
    """.trimIndent()
    val MESH_VERTEX = """
attribute vec2 a_texCoord,a_from,a_to;
uniform float u_warpMix;varying highp vec2 v_uv;
void main(){float k=u_warpMix;gl_Position=vec4(mix(a_from,a_to,k),0.,1.);v_uv=(a_texCoord-.5)*.8+.5;}
    """.trimIndent()
    val MESH = """
precision highp float;uniform sampler2D u_texture;uniform float u_dark,u_luma;
varying highp vec2 v_uv;void main(){vec3 c=texture2D(u_texture,vec2(v_uv.x,1.-v_uv.y)).rgb;
float g=dot(c,vec3(.3,.59,.11));c=min(vec3(.9951171875),vec3(g)+(c-g)*2.);
if(u_dark>.5)c=mix(c,vec3(0.),.25)-.02;
else c=min(vec3(1.),mix(c,vec3(1.),.25)+(u_luma<.3?.38:.08)*.05);
gl_FragColor=vec4(clamp(c,.07,.97),1.);}
    """.trimIndent()
    fun gaussian(sigma: Double): String {
        val taps = MacScene.gaussian(sigma)
        fun num(v: Double) = java.lang.String.format(java.util.Locale.US, "%.10f", v)
        return buildString {
            append("precision highp float; uniform sampler2D u_texture; uniform vec2 u_axis,u_size; varying highp vec2 v_uv;")
            append("vec4 zeroSample(vec2 uv){vec2 f=clamp(min(uv,1.-uv)*u_size+.5,0.,1.);return texture2D(u_texture,uv)*f.x*f.y;}")
            append("void main(){vec2 uv=vec2(v_uv.x,1.-v_uv.y);vec4 c=zeroSample(uv)*${num(taps[0].second)};")
            for ((offset, weight) in taps.drop(1)) {
                append("c+=(zeroSample(uv+u_axis*${num(offset)})+zeroSample(uv-u_axis*${num(offset)}))*${num(weight)};")
            }
            append("gl_FragColor=c;}")
        }
    }
}
