package com.applemusicktv.ui.background

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.*

/** Bounded t=0 approximation of the same Mac scene for static preference/GPU failure. */
internal fun macStaticFrame(context:Context,input:Bitmap,viewWidth:Int,viewHeight:Int,meshIndex:Int):Bitmap {
    val w=128;val h=(w*viewHeight.toFloat()/viewWidth.coerceAtLeast(1)).roundToInt().coerceIn(32,256)
    val scaled=Bitmap.createScaledBitmap(input,64,64,true);val pixels=IntArray(4096);scaled.getPixels(pixels,0,64,0,0,64,64);if(scaled!==input)scaled.recycle()
    val a=FloatArray(w*h*3);val b=FloatArray(a.size);val c=FloatArray(a.size)
    val ax=max(1f,viewHeight.toFloat()/viewWidth.coerceAtLeast(1));val ay=max(1f,viewWidth.toFloat()/viewHeight.coerceAtLeast(1))
    fun image(u:Float,v:Float,k:Int):Float {
        val x=(u*64-.5f).coerceIn(0f,63f);val y=(v*64-.5f).coerceIn(0f,63f);val x0=x.toInt();val y0=y.toInt();val x1=min(63,x0+1);val y1=min(63,y0+1)
        fun channel(xx:Int,yy:Int)=((pixels[yy*64+xx] ushr (16-k*8))and 255)/255f
        val top=channel(x0,y0)*(1-(x-x0))+channel(x1,y0)*(x-x0)
        val bot=channel(x0,y1)*(1-(x-x0))+channel(x1,y1)*(x-x0)
        return top*(1-(y-y0))+bot*(y-y0)
    }
    for(y in 0 until h)for(x in 0 until w){
        val px=(2*(x+.5f)/w-1)/ax;val py=(1-2*(y+.5f)/h)/ay
        for(i in 2 downTo 0){val qx=px-MacScene.offsets[i][0];val qy=py-MacScene.offsets[i][1]
            if(abs(qx)<=1&&abs(qy)<=1){val u=(qx+1)*.5f;val v=(1-qy)*.5f;val rgb=FloatArray(3){image(u,v,it)};val gray=rgb[0]*.3f+rgb[1]*.59f+rgb[2]*.11f
                for(k in 0..2)a[(y*w+x)*3+k]=(gray+(rgb[k]-gray)*1.3f).coerceIn(0f,1f);break}
        }
    }
    val sigma=MacScene.sigma(w,h);val radius=ceil(sigma*3).toInt();val weights=FloatArray(radius*2+1){exp(-.5*(it-radius)*(it-radius)/(sigma*sigma)).toFloat()};val sum=weights.sum();for(i in weights.indices)weights[i]/=sum
    for(y in 0 until h)for(x in 0 until w)for(k in 0..2){var s=0f;for(dx in -radius..radius){val xx=x+dx;if(xx in 0 until w)s+=a[(y*w+xx)*3+k]*weights[dx+radius]};b[(y*w+x)*3+k]=s}
    for(y in 0 until h)for(x in 0 until w)for(k in 0..2){var s=0f;for(dy in -radius..radius){val yy=y+dy;if(yy in 0 until h)s+=b[(yy*w+x)*3+k]*weights[dy+radius]};c[(y*w+x)*3+k]=s}
    val result=IntArray(w*h){0xff000000.toInt()};val mesh=MacMesh(context,meshIndex);val v=mesh.vertices
    fun sample(u:Float,t:Float,k:Int):Float {val fx=(u*w-.5f).coerceIn(0f,w-1f);val fy=(t*h-.5f).coerceIn(0f,h-1f);val x=fx.toInt();val y=fy.toInt();val dx=fx-x;val dy=fy-y
        val a0=c[(y*w+x)*3+k];val a1=c[(y*w+min(w-1,x+1))*3+k];val a2=c[(min(h-1,y+1)*w+x)*3+k];val a3=c[(min(h-1,y+1)*w+min(w-1,x+1))*3+k]
        return (a0*(1-dx)+a1*dx)*(1-dy)+(a2*(1-dx)+a3*dx)*dy}
    for(i in mesh.indices.indices step 3){
        val ids=IntArray(3){(mesh.indices[i+it].toInt() and 0xffff)*6};val xx=FloatArray(3){(1+(v[ids[it]+2]+v[ids[it]+4])*.5f)*w*.5f};val yy=FloatArray(3){(1-(v[ids[it]+3]+v[ids[it]+5])*.5f)*h*.5f}
        val det=(yy[1]-yy[2])*(xx[0]-xx[2])+(xx[2]-xx[1])*(yy[0]-yy[2]);if(abs(det)<1e-8)continue
        val xMin=floor(xx.min()).toInt().coerceAtLeast(0);val xMax=ceil(xx.max()).toInt().coerceAtMost(w-1);val yMin=floor(yy.min()).toInt().coerceAtLeast(0);val yMax=ceil(yy.max()).toInt().coerceAtMost(h-1)
        for(y in yMin..yMax)for(x in xMin..xMax){val px=x+.5f;val py=y+.5f;val p=((yy[1]-yy[2])*(px-xx[2])+(xx[2]-xx[1])*(py-yy[2]))/det;val q=((yy[2]-yy[0])*(px-xx[2])+(xx[0]-xx[2])*(py-yy[2]))/det;val r=1-p-q
            if(p>=-1e-5&&q>=-1e-5&&r>=-1e-5){val u=(p*v[ids[0]]+q*v[ids[1]]+r*v[ids[2]])*.8f+.1f;val t=(p*v[ids[0]+1]+q*v[ids[1]+1]+r*v[ids[2]+1])*.8f+.1f
                val rgb=FloatArray(3){sample(u,t,it)};val gray=rgb[0]*.3f+rgb[1]*.59f+rgb[2]*.11f
                var color=0xff000000.toInt();for(k in 0..2){val n=((min(.9951172f,gray+(rgb[k]-gray)*2)*.75f-.02f).coerceIn(.07f,.97f)*255).roundToInt();color=color or (n shl (16-k*8))};result[y*w+x]=color}
        }
    }
    return Bitmap.createBitmap(result,w,h,Bitmap.Config.ARGB_8888)
}
