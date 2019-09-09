package mini.unittest

import _root_.mini._

import chisel3._
import chisel3.tester._
import chisel3.tester.experimental.TestOptionBuilder._

import org.scalatest._

class AluUnitTest extends FreeSpec with ChiselScalatestTester {
  implicit val p = (new MiniConfig).toInstance

  "Basic ALU operations should work" in {
    test(new ALUArea()).withAnnotations(Seq()) { dut =>

      val aWidth = dut.io.A.getWidth
      val bWidth = dut.io.B.getWidth

      dut.io.alu_op.poke(ALU.ALU_ADD)

      val maxA: BigInt = (BigInt(2) << aWidth) - 1
      val maxB: BigInt = (BigInt(2) << bWidth) - 1
      val random = scala.util.Random

      for(trial <- 0 to 100000) {
        val a = BigInt(aWidth, random)
        val b = BigInt(aWidth, random)
        dut.io.A.poke(a.U)
        dut.io.B.poke(b.U)
        dut.io.out.expect(((a + b) & maxA).U)
        dut.clock.step()
      }
    }
  }
}
