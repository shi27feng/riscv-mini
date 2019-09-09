package mini.unittest

import _root_.mini._
import chisel3.{assert, _}
import chisel3.tester._
import chisel3.iotesters.{Driver, PeekPokeTester, chiselMain, chiselMainTest}
import freechips.rocketchip.config.{Field, Parameters}
import java.io.{File, PrintStream}

import chisel3.tester.ChiselScalatestTester
import org.scalatest.FreeSpec

import scala.collection.mutable
import scala.sys.process.{BasicIO, stringSeqToProcess}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.mutable.{Queue => ScalaQueue}

import scala.reflect.ClassTag

object TestParams {
  implicit def p = new MiniConfig
}

import TestParams.p

object checkBackend {
  def apply(b: String) = {
    val cmd = Seq("bash", "-c", "which %s".format(b))
    b == "firrtl" || (cmd run BasicIO(false, new StringBuffer, None)).exitValue == 0
  }
}

abstract class UnitTest[+M <: Module : ClassTag](c: => M)(tester: M => PeekPokeTester[M])
    extends org.scalatest.FlatSpec {
  val outDir = new File("test-outs") ; outDir.mkdirs
  def baseArgs(dir: File) = Array(
    "--targetDir", dir.getCanonicalPath.toString,
    "--v", "--genHarness", "--compile", "--test")

  val modName = implicitly[ClassTag[M]].runtimeClass.getSimpleName
  val dir = new File(s"$outDir/$modName")
  behavior of modName
  Seq("verilator", "vcs") foreach { backend =>
    if (checkBackend(backend)) {
      it should s"pass $backend" in {
        val args = baseArgs(dir) ++ Array(
          "--backend", backend,
          "--logFile", (new File(dir, s"$modName-$backend.log")).toString)
        chiselMainTest(args, () => c)(tester)
      }
    } else {
      ignore should s"pass $backend" in { assert(false) }
    }
  }
}

class AluSimpleUnitTest extends FreeSpec with ChiselScalatestTester with RandomInstructions {
  class AluTests(c: ALU) {
    for (inst <- instructions) {
      val a = rand_data
      val b = rand_data
      val ctrl = GoldControl(new ControlIn(inst))
      val gold = GoldALU(new ALUIn(ctrl.alu_op, a, b))
      println(s"*** ${dasm(inst)} -> A: %x, B: %x".format(a, b))
      c.io.A.poke(a.U)
      c.io.B.poke(b.U)
      c.io.alu_op.poke(ctrl.alu_op.U)
      c.io.out.expect(gold.out.U)
      c.io.sum.expect(gold.sum.U)
    }
  }
  "ALU should pass its tests" in {
    test(new ALUSimple()(p)) { c =>
      new AluTests(c)
    }
  }

  "ALU AREA should pass its tests" in {
    test(new ALUArea()(p)) { c =>
      new AluTests(c)
    }
  }
}

class BranchConditionSimpleTest extends FreeSpec with ChiselScalatestTester with RandomInstructions {
  class BranchConditionalTests(c: BrCond) {
    val insts = List.fill(10) {
      List(
        B(Funct3.BEQ, 0, 0, 0),
        B(Funct3.BNE, 0, 0, 0),
        B(Funct3.BLT, 0, 0, 0),
        B(Funct3.BGE, 0, 0, 0),
        B(Funct3.BLTU, 0, 0, 0),
        B(Funct3.BGEU, 0, 0, 0)
      )
    }.flatten

    for (inst <- insts) {
      val a = rand_data
      val b = rand_data
      val ctrl = GoldControl(new ControlIn(inst))
      val gold = GoldBrCond(new BrCondIn(ctrl.br_type, a, b))
      println(s"*** ${dasm(inst)} -> A: %x, B: %x ***".format(a, b))
      c.io.br_type.poke(ctrl.br_type.U)
      c.io.rs1.poke(a.U)
      c.io.rs2.poke(b.U)
      c.io.taken.expect(gold.taken.B)
    }
  }

  "Simple branch condition should pass" in {
    test(new BrCondSimple()(p)) { c =>
      new BranchConditionalTests(c)
    }
  }
}


class CacheUnitTest extends FreeSpec with ChiselScalatestTester with RandomInstructions {

  class Mem(
             cache_ar_Q: ScalaQueue[TestNastiReadAddr], gold_ar_Q: ScalaQueue[TestNastiReadAddr],
             cache_aw_Q: ScalaQueue[TestNastiWriteAddr], gold_aw_Q: ScalaQueue[TestNastiWriteAddr],
             cache_r_Q: ScalaQueue[TestNastiReadData], gold_r_Q: ScalaQueue[TestNastiReadData],
             cache_w_Q: ScalaQueue[TestNastiWriteData], gold_w_Q: ScalaQueue[TestNastiWriteData],
             word_width: Int = 16, depth: Int = 1 << 20, verbose: Boolean = true)(
             implicit logger: java.io.PrintStream) extends SimMem(word_width, depth, verbose)(logger) {
    var aw: Option[TestNastiWriteAddr] = None

    def process = aw match {
      case Some(p) if cache_w_Q.size >= p.len && gold_w_Q.size >= p.len =>
        val addr = p.addr >> off
        val size = 8 * (1 << p.size)
        write(addr, ((0 until p.len) foldLeft BigInt(0)) { (data, i) =>
          val cache_w = cache_w_Q.dequeue
          val gold_w = gold_w_Q.dequeue
          assert(cache_w == gold_w,
            s"\n*Cache* => ${cache_w}\n*Gold*  => ${gold_w}")
          assert(i != p.len || cache_w.last, cache_w.toString)
          data | (cache_w.data << size)
        })
        aw = None
      case None if !cache_aw_Q.isEmpty && !gold_aw_Q.isEmpty =>
        val cache_aw = cache_aw_Q.dequeue
        val gold_aw = gold_aw_Q.dequeue
        assert(cache_aw == gold_aw,
          s"\n*Cache* => ${cache_aw}\n*Gold*  => ${gold_aw}")
        aw = Some(cache_aw)
      case None if !cache_ar_Q.isEmpty && !gold_ar_Q.isEmpty =>
        val cache_ar = cache_ar_Q.dequeue
        val gold_ar = gold_ar_Q.dequeue
        assert(cache_ar == gold_ar,
          s"\n*Cache* => ${cache_ar}\n*Gold*  => ${gold_ar}")
        val size = 8 * (1 << cache_ar.size)
        val addr = cache_ar.addr >> off
        val mask = (BigInt(1) << size) - 1
        val data = read(addr)
        (0 to cache_ar.len) foreach { i =>
          val r_data = new TestNastiReadData(
            cache_ar.id, (data >> (i * size)) & mask, i == cache_ar.len)
          cache_r_Q enqueue r_data
          gold_r_Q enqueue r_data
        }
      case _ =>
    }
  }

  //TODO: Revive this code
  class CacheTests(c: Cache) {
    implicit def bigIntToInt(b: BigInt) = b.toInt

    implicit def bigIntToBoolean(b: BigInt) = b != BigInt(0)

    implicit def booleanToBigInt(b: Boolean) = if (b) BigInt(1) else BigInt(0)

    def int(i: Int): BigInt = (BigInt(i >>> 1) << 1) | (i & 1)

    class ValidWatcher[T <: Data](validIo: T, post:

    // CacheIO
    val req_h = new ValidSource(c.io.cpu.req, (req: CacheReq, in: TestCacheReq) => {
      reg_poke(req.addr, in.addr);
      reg_poke(req.data, in.data);
      reg_poke(req.mask, in.mask)
    })


    val resp_h = new ValidSink(c.io.cpu.resp,
      (resp: CacheResp) => new TestCacheResp(peek(resp.data)))
    // NastiIO
    val ar_h = new DecoupledSink(c.io.nasti.ar,
      (ar: NastiReadAddressChannel) => new TestNastiReadAddr(
        peek(ar.id), peek(ar.addr), peek(ar.size), peek(ar.len)))
    val aw_h = new DecoupledSink(c.io.nasti.aw,
      (aw: NastiWriteAddressChannel) => new TestNastiWriteAddr(
        peek(aw.id), peek(aw.addr), peek(aw.size), peek(aw.len)))
    val w_h = new DecoupledSink(c.io.nasti.w, (w: NastiWriteDataChannel) =>
      new TestNastiWriteData(peek(w.data), peek(w.last)))
    val r_h = new DecoupledSource(c.io.nasti.r, (r: NastiReadDataChannel, in: TestNastiReadData) => {
      reg_poke(r.id, in.id);
      reg_poke(r.data, in.data);
      reg_poke(r.last, in.last)
    })

    val gold = new GoldCache(c.nSets, c.bBytes, c.xlen, c.nastiXDataBits / 8)
    val mem = new Mem(
      ar_h.outputs, gold.nasti_ar,
      aw_h.outputs, gold.nasti_aw,
      r_h.inputs, gold.nasti_r,
      w_h.outputs, gold.nasti_w, c.bBytes)
    preprocessors += gold
    preprocessors += mem

    def rand_tag = rnd.nextInt(1 << c.tlen)

    def rand_idx = rnd.nextInt(1 << c.slen)

    def rand_off = rnd.nextInt(1 << c.blen) & -4

    def rand_data = ((0 until c.bBytes) foldLeft BigInt(0)) ((r, i) =>
      r | (BigInt(rnd.nextInt() & 0xff) << 8 * i))

    def rand_mask = (1 << (c.xlen / 8)) - 1

    def addr(tag: Int, idx: Int, off: Int) = ((tag << c.slen | idx) << c.blen) | off

    def init(tags: Vector[Int], idxs: Vector[Int]) {
      for (tag <- tags; idx <- idxs) {
        mem.write(tag << c.slen | idx, rand_data)
      }
    }

    private var testCnt = 0

    def test(tag: Int, idx: Int, off: Int, mask: Int = 0) = {
      println(s"***** TEST ${testCnt} *****")

      val req = new TestCacheReq(addr(tag, idx, off), int(rnd.nextInt), mask)

      gold.cpu_reqs enqueue req
//      req_h.inputs enqueue req
//      println(req.toString)

      c.io.cpu.resp.valid.expect(true.B)
      println(c.io.cpu.resp.peek())

      // cache access
      req_h.process
      takestep {
        resp_h.outputs.clear
      }

      if (eventually(!resp_h.outputs.isEmpty && !gold.cpu_resps.isEmpty, 10)) {
        val cacheResp = resp_h.outputs.dequeue
        val goldResp = gold.cpu_resps.dequeue
        if (mask == 0) {
          assert(cacheResp.data == goldResp.data,
            s"\n*Cache* => ${cacheResp}\n*Gold*  => ${goldResp}")
        }
      }
      resp_h.outputs.clear
      gold.cpu_resps.clear
      testCnt += 1
    }

    val tags = Vector(rand_tag, rand_tag, rand_tag)
    val idxs = Vector(rand_idx, rand_idx)
    val offs = Vector(rand_off, rand_off, rand_off, rand_off, rand_off, rand_off)

    init(tags, idxs)
    test(tags(0), idxs(0), offs(0)) // #0: read miss
    test(tags(0), idxs(0), offs(1)) // #1: read hit
    test(tags(1), idxs(0), offs(0)) // #2: read hit
    test(tags(1), idxs(0), offs(2)) // #3: read miss
    test(tags(1), idxs(0), offs(3)) // #4: read hit
    test(tags(1), idxs(0), offs(4), rand_mask) // #5: write hit
    test(tags(1), idxs(0), offs(4)) // #6: read hit
    test(tags(2), idxs(0), offs(5)) // #7: read miss & write back
    test(tags(0), idxs(1), offs(0), rand_mask) // #8: write miss
    test(tags(0), idxs(1), offs(0)) // #9: read hit
    test(tags(0), idxs(1), offs(1)) // #10: read hit
    test(tags(1), idxs(1), offs(2), rand_mask) // #11: write miss & write back
    test(tags(1), idxs(1), offs(3)) // #12: read hit
    test(tags(2), idxs(1), offs(4)) // #13: read write back
    test(tags(2), idxs(1), offs(5)) // #14: read hit
  }

  "Simple branch condition should pass" in {
    test(new Cache()(p)) { c =>
      new CacheTests(c)
    }
  }
}


class CoreUnitTest extends FreeSpec with ChiselScalatestTester with RandomInstructions {

  class CoreSimpleTests(c: Core) {
    val evec = Const.PC_EVEC

    def doTest(test: List[chisel3.UInt]) {
      val mem = mutable.HashMap[Int, Byte]() // mock memory
      // Requires at least 5 cycles of reset

      for (i <- 0 until c.dpath.regFile.regs.length) {
        /* TODO:
        if (i == 0)
          pokeAt(c.dpath.regFile.regs, 0, i)
        else
          pokeAt(c.dpath.regFile.regs, int(rnd.nextInt() & 0xffffffff), i)
        */
      }

      do {
        // InstMem
        val iaddr = c.io.icache.req.bits.addr.peek().litValue()
        val daddr = c.io.dcache.req.bits.addr.peek().litValue()
        val idx = (iaddr - Const.PC_START).toInt / 4
        val inst = if (iaddr == evec + (3 << 6)) fin
        else if (idx < test.size && idx >= 0) test(idx) else nop
        val dout = ((0 until 4) foldLeft BigInt(0)) {
          (res, i) => res | mem.getOrElse(daddr + i, 0.toByte) << 8 * i
        }
        val dwe = c.io.dcache.req.bits.mask.peek().litValue()
        val din = c.io.dcache.req.bits.data.peek().litValue()
        val ire = c.io.icache.req.valid.peek().litToBoolean
        val dre = c.io.dcache.req.valid.peek().litToBoolean

        c.clock.step(1)
        if (ire) {
          println(s"FEED: ${dasm(inst)}")
          c.io.icache.resp.bits.data.poke(inst)
        }
        if (dre) {
          if (dwe) {
            (0 until 4).filter { i => (dwe >> i) & 1}.foreach { i =>
              mem(daddr + i) = (din >> 8 * i).toByte
              println("MEM[%x] <- %x".format(daddr + i, mem(daddr + i)))
            }
          } else {
            println("MEM[%x] -> %x".format(daddr, dout))
            c.io.dcache.resp.bits.data.poke(dout.U)
          }
        }
      } while (c.io.host.tohost.peek().litValue() == 0)

      //TODO: Figure out how to peek the memory as was done before
//      for ((rd, expected) <- testResults(test)) {
//        val result = c.dpath.regFile.regs(rd).peek()
//        assert(result == expected, "RegFile[%d] = %d == %d".format(rd, result, expected))
//      }
    }

    c.io.icache.resp.valid.poke(true.B)
    c.io.dcache.resp.valid.poke(true.B)
    doTest(bypassTest)
    doTest(exceptionTest)
  }

  "Simple branch condition should pass" in {
    test(new Core()(p)) { c =>
      new CoreSimpleTests(c)
    }
  }
}


//class ALUSimpleUnitTest extends UnitTest(new ALUSimple()(p))(m => new ALUTests(m))
//class ALUAreaUnitTest extends UnitTest(new ALUArea()(p))(m => new ALUTests(m))
//class BrCondSimpleUnitTest extends UnitTest(new BrCondSimple()(p))(m => new BrCondTests(m))
//class ImmGenWireUnitTest extends UnitTest(new ImmGenWire()(p))(m => new ImmGenTests(m))
//class ImmGenMuxUnitTest extends UnitTest(new ImmGenMux()(p))(m => new ImmGenTests(m))
//class ControlUnitTest extends UnitTest(new Control()(p))(m => new ControlTests(m))
//class CSRUnitTest extends UnitTest(new CSR()(p))(m => new CSRTests(m))
//class DatapathUnitTest extends UnitTest(new Datapath()(p))(m => new DatapathTests(m))
//class CacheUnitTest extends UnitTest(new Cache()(p))(m => new CacheTests(m))
//class CoreUnitTest extends UnitTest(new Core()(p))(m => new CoreSimpleTests(m))

//abstract class MiniTestSuite[+T <: Module : ClassTag](
//    dutGen: => T,
//    backend: String,
//    testType: TestType,
//    N: Int = 5,
//    latency: Int = 8) extends org.scalatest.FlatSpec {
//  val dutName = implicitly[ClassTag[T]].runtimeClass.getSimpleName
//  val baseDir = new File(s"test-outs/$dutName") ; baseDir.mkdirs
//  val dir = new File(baseDir, testType.toString) ; dir.mkdirs
//  val args = Array(
//    "--targetDir", dir.toString, "--backend", backend,
//    "--v", "--genHarness", "--compile", "--test")
//
//  behavior of s"$dutName in $backend"
//  if (checkBackend(backend)) {
//    import scala.concurrent.duration._
//    import ExecutionContext.Implicits.global
//    val dut = chiselMain(args, () => dutGen)
//    val vcs = backend == "vcs"
//    val results = testType.tests.zipWithIndex sliding (N, N) map { subtests =>
//      val subresults = subtests map {case (t, i) =>
//        val loadmem = getClass.getResourceAsStream(s"/$t.hex")
//        val logFile = Some(new File(dir, s"$t-$backend.log"))
//        val waveform = Some(new File(dir, s"$t.%s".format(if (vcs) "vpd" else "vcd")))
//        val testCmd = new File(dir, s"%s$dutName".format(if (vcs) "" else "V"))
//        val args = new MiniTestArgs(loadmem, logFile, false, testType.maxcycles, latency)
//        Future(t -> (dut match {
////          case _: Core => Driver.run(
////            () => dutGen.asInstanceOf[Core], testCmd, waveform)(m => new CoreTester(m)(args))
//          case _: Tile => Driver.run(
//            () => dutGen.asInstanceOf[Tile], testCmd, waveform)(m => new TileTester(m, args))
//        }))
//      }
//      Await.result(Future.sequence(subresults), Duration.Inf)
//    }
//    results.flatten foreach { case (name, pass) => it should s"pass $name" in { assert(pass) } }
//  } else {
//    testType.tests foreach { name => ignore should s"pass $name" in { assert(false) } }
//  }
//}
//
//class CoreCppISATests extends MiniTestSuite(new Core()(p), "verilator", ISATests)
//class CoreCppBmarkTests extends MiniTestSuite(new Core()(p), "verilator", BmarkTests)
//class CoreVCSISATests extends MiniTestSuite(new Core()(p), "vcs", ISATests)
//class CoreVCSBmarkTests extends MiniTestSuite(new Core()(p), "vcs", BmarkTests)
//
//class TileCppISATests extends MiniTestSuite(new Tile(p), "verilator", ISATests)
//class TileCppBmarkTests extends MiniTestSuite(new Tile(p), "verilator", BmarkTests)
//class TileVCSISATests extends MiniTestSuite(new Tile(p), "vcs", ISATests)
//class TileVCSBmarkTests extends MiniTestSuite(new Tile(p), "vcs", BmarkTests)
