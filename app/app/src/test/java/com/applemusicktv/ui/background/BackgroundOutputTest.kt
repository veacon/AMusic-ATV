package com.applemusicktv.ui.background
import org.junit.Assert.*
import org.junit.Test
class BackgroundOutputTest {
 @Test fun outputCapsAndAspect() {
  assertEquals(BackgroundOutputSize(640,360),backgroundOutputSize(1920,1080,FluidQuality.POWER_SAVE))
  assertEquals(BackgroundOutputSize(960,540),backgroundOutputSize(3840,2160,FluidQuality.STANDARD))
  assertEquals(BackgroundOutputSize(1280,720),backgroundOutputSize(3840,2160,FluidQuality.HIGH_QUALITY))
  assertEquals(BackgroundOutputSize(960,600),backgroundOutputSize(1920,1200,FluidQuality.STANDARD))
  assertEquals(BackgroundOutputSize(1280,960),backgroundOutputSize(1600,1200,FluidQuality.HIGH_QUALITY))
  assertEquals(BackgroundOutputSize(360,640),backgroundOutputSize(1080,1920,FluidQuality.POWER_SAVE))
  assertEquals(BackgroundOutputSize(320,180),backgroundOutputSize(320,180,FluidQuality.HIGH_QUALITY))
 }
 @Test fun mediumGpuSizesAndBlurScale() {
  assertEquals(IntermediatePlan(128,72,6.5),intermediatePlan(1920,1080,256,FluidQuality.POWER_SAVE))
  assertEquals(IntermediatePlan(128,72,6.5),intermediatePlan(1920,1080,256,FluidQuality.STANDARD))
  assertEquals(IntermediatePlan(192,108,9.75),intermediatePlan(1920,1080,256,FluidQuality.HIGH_QUALITY))
  assertEquals(IntermediatePlan(128,96,7.0),intermediatePlan(1600,1200,256,FluidQuality.STANDARD))
  assertEquals(IntermediatePlan(192,144,10.5),intermediatePlan(1600,1200,256,FluidQuality.HIGH_QUALITY))
 }
 @Test fun highPrecisionGpuAlsoHasAbsoluteCapAndPreservesScale() {
  val s=intermediatePlan(1920,1080,640,FluidQuality.STANDARD)
  assertEquals(128,s.width);assertEquals(72,s.height);assertEquals(6.6,s.sigma,1e-10)
  val h=intermediatePlan(1920,1080,640,FluidQuality.HIGH_QUALITY)
  assertEquals(192,h.width);assertEquals(108,h.height);assertEquals(9.9,h.sigma,1e-10)
  val tiny=intermediatePlan(80,40,640,FluidQuality.HIGH_QUALITY)
  assertEquals(80,tiny.width);assertEquals(40,tiny.height)
 }
 @Test fun legacySettingsAndPriority() {
  assertEquals(FluidQuality.POWER_SAVE,FluidQuality.fromName("POWER_SAVE"))
  assertEquals(FluidQuality.STANDARD,FluidQuality.fromName("STANDARD"))
  assertEquals(FluidQuality.STANDARD,FluidQuality.fromName("HIGH"))
  assertEquals(FluidQuality.HIGH_QUALITY,FluidQuality.fromName("HIGH_QUALITY"))
  assertEquals(FluidQuality.POWER_SAVE,unifiedFluidQuality(FluidQuality.HIGH_QUALITY,false,true))
  assertEquals(FluidQuality.STATIC,unifiedFluidQuality(FluidQuality.HIGH_QUALITY,true,true))
  assertEquals(FluidQuality.OFF,unifiedFluidQuality(FluidQuality.OFF,true,true))
  assertEquals(listOf("OFF","STATIC","POWER_SAVE","STANDARD","HIGH_QUALITY"),FluidQuality.entries.map { it.name })
  assertTrue(FluidQuality.entries.filter { it.animated }.all { it.fps==60 })
 }
 @Test fun vsyncCapsHighRefreshAndFollowsLowerRefresh() {
  fun count(hz:Int):Int {
   val cadence=BackgroundFrameCadence()
   return (0 until hz).count { cadence.shouldRender(1_000_000_000L+it*1_000_000_000L/hz,hz.toFloat()) }
  }
  assertEquals(60,count(60));assertEquals(60,count(120));assertEquals(50,count(50));assertEquals(60,count(90))
 }
}
