package com.applemusicktv.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20 as GL
import android.opengl.GLUtils
import com.applemusicktv.data.NetworkLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** ES2 owner-thread renderer; no Apple binary, WebView, GLES3 or audio processing. */
internal class MacRenderer(private val context: Context, private val clock: MacClock) {
    private class Program(vertex: String, fragment: String) {
        val id: Int
        private val locations=HashMap<String,Int>()
        init {
            val v=shader(GL.GL_VERTEX_SHADER,vertex)
            val f=try { shader(GL.GL_FRAGMENT_SHADER,fragment) } catch(e:Exception){GL.glDeleteShader(v);throw e}
            id=GL.glCreateProgram(); GL.glAttachShader(id,v); GL.glAttachShader(id,f);GL.glLinkProgram(id)
            GL.glDeleteShader(v);GL.glDeleteShader(f)
            val ok=IntArray(1);GL.glGetProgramiv(id,GL.GL_LINK_STATUS,ok,0)
            if(ok[0]==0){val reason=GL.glGetProgramInfoLog(id);GL.glDeleteProgram(id);error("Mac link: $reason")}
        }
        fun location(n:String)=locations.getOrPut(n){GL.glGetUniformLocation(id,n)}
        fun attr(n:String)=GL.glGetAttribLocation(id,n)
        fun float(n:String,v:Float)=GL.glUniform1f(location(n),v)
        fun pair(n:String,x:Float,y:Float)=GL.glUniform2f(location(n),x,y)
        fun texture(n:String,t:Int,unit:Int=0){GL.glActiveTexture(GL.GL_TEXTURE0+unit);GL.glBindTexture(GL.GL_TEXTURE_2D,t);GL.glUniform1i(location(n),unit)}
        companion object {
            fun shader(kind:Int,code:String):Int{
                val s=GL.glCreateShader(kind);GL.glShaderSource(s,code);GL.glCompileShader(s)
                val ok=IntArray(1);GL.glGetShaderiv(s,GL.GL_COMPILE_STATUS,ok,0)
                if(ok[0]==0){val reason=GL.glGetShaderInfoLog(s);GL.glDeleteShader(s);error("Mac shader: $reason")}
                return s
            }
        }
    }
    private data class Target(val texture:Int,val fbo:Int,val w:Int,val h:Int)
    private val programs=ArrayList<Program>()
    private val targets=ArrayList<Target>()
    private lateinit var rotation:Program
    private lateinit var meshProgram:Program
    private var blur:Program?=null
    private var blurSigma:Double?=null
    private lateinit var mesh:MacMesh
    private val quad=floats(-1f,-1f,0f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f, 1f,1f,1f,0f, 1f,-1f,1f,1f, -1f,-1f,0f,1f)
    private var highp=true
    private var maxSize=2048
    private var image=0
    private var outgoing=0
    private var transition=MacScene.TRANSITION_SECONDS
    private var lastFrame=0L
    private var a:Target?=null
    private var b:Target?=null
    private var c:Target?=null

    fun initialize(){
        val range=IntArray(2);val precision=IntArray(1)
        GL.glGetShaderPrecisionFormat(GL.GL_FRAGMENT_SHADER,GL.GL_HIGH_FLOAT,range,0,precision,0)
        highp=precision[0]>0
        rotation=program(MacShaders.ROTATION_VERTEX,MacShaders.ROTATION)
        meshProgram=program(MacShaders.MESH_VERTEX,MacShaders.MESH)
        mesh=MacMesh(context, clock.meshIndex)
        val lim=IntArray(1);GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE,lim,0);maxSize=lim[0]
        GL.glGetIntegerv(GL.GL_MAX_RENDERBUFFER_SIZE,lim,0);maxSize=min(maxSize,lim[0])
        GL.glDisable(GL.GL_DEPTH_TEST);GL.glDisable(GL.GL_BLEND);GL.glDisable(GL.GL_DITHER)
        NetworkLog.line("MacFluid","GLES2 ${GL.glGetString(GL.GL_RENDERER)}; ${if(highp) "highp" else "mediump"}; mesh=${clock.meshIndex + 1}/5; display-vsync")
        checkGl()
    }
    private fun source(s:String)=if(highp)s else s.replace("highp","mediump")
    private fun program(v:String,f:String)=Program(source(v),source(f)).also{programs+=it}
    fun setArtwork(bitmap:Bitmap?,now:Long){
        if(outgoing!=0)GL.glDeleteTextures(1,intArrayOf(outgoing),0)
        outgoing=image;image=0
        if(bitmap!=null){image=texture();GLUtils.texImage2D(GL.GL_TEXTURE_2D,0,bitmap,0)}
        transition=if(outgoing==0)MacScene.TRANSITION_SECONDS else 0.0
        checkGl()
    }
    fun render(width:Int,height:Int,state:FluidRenderState,now:Long,outputWidth:Int=width,outputHeight:Int=height):Boolean{
        if(width<=0||height<=0)return false
        val dt=if(lastFrame==0L)0.0 else ((now-lastFrame)/1e9).coerceIn(0.0,.1)
        lastFrame=now;clock.time=MacScene.advanceTime(clock.time,dt);transition+=dt
        if(image==0)return false
        val cap=min(MacScene.maxEdge(state.quality,highp),maxSize)
        val plan=intermediatePlan(width,height,cap,state.quality)
        val w=plan.width;val h=plan.height
        val sizeChanged=a?.w!=w||a?.h!=h
        if(sizeChanged){
            targets.toList().forEach(::delete);a=null;b=null;c=null
            a=target(w,h);b=target(w,h);c=target(w,h)
        }
        if(sizeChanged || blurSigma != plan.sigma){
            blur?.let {GL.glDeleteProgram(it.id);programs.remove(it)};blur=null
            blur=program(MacShaders.VERTEX,MacShaders.gaussian(plan.sigma));blurSigma=plan.sigma
            NetworkLog.line("MacFluid","scene=${w}x$h sigma=${plan.sigma}; quality=${state.quality.name}; mesh=${clock.meshIndex + 1}/5 1681/3200; viewport=${width}x$height")
        }
        use(rotation,a!!);GL.glClearColor(0f,0f,0f,1f);GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        rotation.texture("u_texture",image);rotation.texture("u_old",if(outgoing==0)image else outgoing,1)
        rotation.float("u_mix",(transition/MacScene.TRANSITION_SECONDS).coerceIn(0.0,1.0).toFloat())
        rotation.pair("u_aspect",max(1f,height.toFloat()/width),max(1f,width.toFloat()/height))
        for(i in 0..2){rotation.float("u_angle",MacScene.angle(clock.time,i));rotation.pair("u_offset",MacScene.offsets[i][0],MacScene.offsets[i][1]);drawQuad(rotation)}
        if(outgoing!=0&&transition>=MacScene.TRANSITION_SECONDS){GL.glDeleteTextures(1,intArrayOf(outgoing),0);outgoing=0}
        val gaussian=checkNotNull(blur)
        use(gaussian,b!!);gaussian.texture("u_texture",a!!.texture);gaussian.pair("u_size",w.toFloat(),h.toFloat());gaussian.pair("u_axis",1f/w,0f);drawQuad(gaussian)
        use(gaussian,c!!);gaussian.texture("u_texture",b!!.texture);gaussian.pair("u_axis",0f,1f/h);drawQuad(gaussian)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0);GL.glViewport(0,0,outputWidth,outputHeight);GL.glUseProgram(meshProgram.id)
        GL.glClearColor(0f,0f,0f,1f);GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        meshProgram.texture("u_texture",c!!.texture);meshProgram.float("u_warpMix",MacScene.warpMix(clock.time));meshProgram.float("u_dark",1f);meshProgram.float("u_luma",.5f)
        disableAttributes()
        for((name,offset)in listOf("a_texCoord" to 0,"a_from" to 2,"a_to" to 4)){
            val loc=meshProgram.attr(name);if(loc>=0){mesh.vertexBuffer.position(offset);GL.glEnableVertexAttribArray(loc);GL.glVertexAttribPointer(loc,2,GL.GL_FLOAT,false,24,mesh.vertexBuffer)}
        }
        mesh.indexBuffer.position(0);GL.glDrawElements(GL.GL_TRIANGLES,mesh.indices.size,GL.GL_UNSIGNED_SHORT,mesh.indexBuffer)
        checkGl();return true
    }
    private fun drawQuad(p:Program){
        disableAttributes()
        for((name,offset)in listOf("a_position" to 0,"a_texCoord" to 2)){
            val loc=p.attr(name);if(loc>=0){quad.position(offset);GL.glEnableVertexAttribArray(loc);GL.glVertexAttribPointer(loc,2,GL.GL_FLOAT,false,16,quad)}
        }
        GL.glDrawArrays(GL.GL_TRIANGLES,0,6)
    }
    private fun disableAttributes(){for(i in 0..7)GL.glDisableVertexAttribArray(i)}
    private fun use(p:Program,t:Target){GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,t.fbo);GL.glViewport(0,0,t.w,t.h);GL.glUseProgram(p.id)}
    private fun texture():Int{val n=IntArray(1);GL.glGenTextures(1,n,0);GL.glBindTexture(GL.GL_TEXTURE_2D,n[0]);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE);return n[0]}
    private fun target(w:Int,h:Int):Target{val t=texture();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null);val f=IntArray(1);GL.glGenFramebuffers(1,f,0);GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,f[0]);GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,t,0);if(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER)!=GL.GL_FRAMEBUFFER_COMPLETE){GL.glDeleteTextures(1,intArrayOf(t),0);GL.glDeleteFramebuffers(1,f,0);error("Mac FBO incomplete")};return Target(t,f[0],w,h).also{targets+=it}}
    private fun delete(t:Target){GL.glDeleteTextures(1,intArrayOf(t.texture),0);GL.glDeleteFramebuffers(1,intArrayOf(t.fbo),0);targets.remove(t)}
    fun resetFrameClock(){lastFrame=0L}
    fun release(){targets.toList().forEach(::delete);programs.forEach{GL.glDeleteProgram(it.id)};programs.clear();if(image!=0)GL.glDeleteTextures(1,intArrayOf(image),0);if(outgoing!=0)GL.glDeleteTextures(1,intArrayOf(outgoing),0);image=0;outgoing=0}
    private fun checkGl(){val e=GL.glGetError();check(e==GL.GL_NO_ERROR){"Mac GL 0x${e.toString(16)}"}}
    companion object {private fun floats(vararg f:Float)=ByteBuffer.allocateDirect(f.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(f);position(0)}}
}
