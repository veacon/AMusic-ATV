package com.applemusicktv.ui.background
import org.junit.Assert.*
import org.junit.Test
class MacSceneTest {
 @Test fun sceneChoosesAcrossAllFiveMeshesAndKeepsItsChoiceDuringAnimation(){
  val random = kotlin.random.Random(20261022)
  val choices = mutableSetOf<Int>()
  repeat(200){
   val clock = MacClock(random)
   val selected = clock.meshIndex
   assertTrue(selected in 0 until MacScene.MESH_COUNT)
   choices += selected
   repeat(60){clock.time = MacScene.advanceTime(clock.time, 1.0 / 30)}
   assertEquals(selected, clock.meshIndex)
  }
  assertEquals((0 until MacScene.MESH_COUNT).toSet(), choices)
 }

 @Test fun retiredHighQualityPreferenceRestoresAsStandard(){
  assertEquals(FluidQuality.STANDARD,FluidQuality.fromName("HIGH"))
 }

 @Test fun seventyPercentMotionKeepsCoverTransitionIndependent(){
  assertEquals(4.9,MacScene.advanceTime(0.0,7.0),1e-10)
  assertEquals(.5f,MacScene.warpMix(MacScene.advanceTime(0.0,5.0)),.000001f)
  assertEquals(0.0,MacScene.advanceTime(1253.0,10.0),1e-10)
  assertEquals(1.0,MacScene.TRANSITION_SECONDS,0.0)
 }

 @Test fun meshExtremesAndContinuousCycle(){
  assertEquals(.5f,MacScene.warpMix(0.0),.000001f)
  assertEquals(0f,MacScene.warpMix(.875),.000001f)
  assertEquals(1f,MacScene.warpMix(2.625),.000001f)
  for(t in listOf(0.0,.1,1.75,3.0,35.4))assertEquals(MacScene.warpMix(t),MacScene.warpMix(t+3.5),.000001f)
 }
 @Test fun sharedClockWrapDoesNotJumpMeshOrModels(){
  assertEquals(MacScene.warpMix(0.0),MacScene.warpMix(1260.0),.00001f)
  for(i in 0..2){val angle=MacScene.angle(1260.0,i);assertTrue(kotlin.math.abs(kotlin.math.sin(angle))<.00001)}
 }
 @Test fun pairedGaussianPreservesEnergyAndMonotonicOffsets(){
  for(sigma in listOf(.5,5.0,33.0,51.0)){
   val taps=MacScene.gaussian(sigma)
   assertEquals(1.0,taps[0].second+2*taps.drop(1).sumOf{it.second},1e-10)
   assertTrue(taps.all{it.second>0&&it.first>=0})
   assertTrue(taps.zipWithNext().all{it.first.first<it.second.first})
  }
 }
 @Test fun sigmaFollowsPixelDiagonalAndDemoResolution(){
  assertEquals(34.0,MacScene.sigma(640,400),0.0)
  assertEquals(33.0,MacScene.sigma(640,360),0.0)
  assertEquals(640,MacScene.maxEdge(FluidQuality.STANDARD))
  assertEquals(320,MacScene.maxEdge(FluidQuality.POWER_SAVE))
  assertEquals(256,MacScene.maxEdge(FluidQuality.STANDARD,false))
  assertEquals(256,MacScene.maxEdge(FluidQuality.POWER_SAVE,false))
 }
}
