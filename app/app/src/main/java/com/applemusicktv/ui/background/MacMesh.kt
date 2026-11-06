package com.applemusicktv.ui.background

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

internal class MacMesh(context: Context, mesh: Int) {
    val vertices: FloatArray
    val indices: ShortArray
    val vertexBuffer: FloatBuffer
    val indexBuffer: ShortBuffer
    init {
        val bytes = context.assets.open("macfluid/mesh.bin").use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(b.int == 0x314d434d) { "Invalid Mac mesh asset" }
        val meshes=b.int; val count=b.int; val indexCount=b.int
        check(meshes==MacScene.MESH_COUNT && count==1681 && indexCount==9600 && mesh in 0 until meshes)
        check(bytes.size==16+meshes*count*24+indexCount*2)
        b.position(16+mesh*count*24)
        vertices=FloatArray(count*6) { b.float }
        b.position(16+meshes*count*24)
        indices=ShortArray(indexCount) { b.short }
        check(indices.all { (it.toInt() and 0xffff)<count })
        vertexBuffer=ByteBuffer.allocateDirect(vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices);position(0) }
        indexBuffer=ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices);position(0) }
    }
}
