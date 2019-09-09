package mini.unittest

import chisel3._
import chisel3.util.log2Up
import chisel3.iotesters._
import junctions._

import scala.collection.mutable.{Queue => ScalaQueue}
import java.io.{File, InputStream, PrintStream}

import _root_.mini._
import chisel3.tester.ChiselScalatestTester
import org.scalatest.{FreeSpec, run}

//case class MiniTestArgs(
//  loadmem: InputStream,
//  logFile: Option[File] = None,
//  verbose: Boolean = false,
//  maxcycles: Long = 500000,
//  memlatency: Int = 5)

//class NewCoreTester(c: Core, args: MiniTestArgs) extends FreeSpec with ChiselScalatestTester {
//  "core unit tests" in {
//    test(c) { c =>
//      c.io.icache.req.initSource()
//    }
//  }
//
//  val ireqProcess = (req: CacheReq) => new TestCacheReq(peek(req.addr), peek(req.data), peek(req.mask))
//
//  val ireqHandler = new ValidSink(
//    c,
//    ireqProcess
//
//  )
//  val dreqHandler = ValidSink(c.io.dcache.req)
//  val irespHandler = new ValidSource(c.io.icache.resp,
//    (resp: CacheResp, in: TestCacheResp) => reg_poke(resp.data, in.data))
//  val drespHandler = new ValidSource(c.io.dcache.resp,
//    (resp: CacheResp, in: TestCacheResp) => reg_poke(resp.data, in.data))
//  val mem = new CoreMem(ireqHandler.outputs, irespHandler.inputs,
//    dreqHandler.outputs, drespHandler.inputs, peek(c.io.dcache.abort),
//    4, verbose=args.verbose)
////  val ireqHandler = new ValidSink(c.io.icache.req,
////    (req: CacheReq) => new TestCacheReq(peek(req.addr), peek(req.data), peek(req.mask)))
////  val dreqHandler = new ValidSink(c.io.dcache.req,
////    (req: CacheReq) => new TestCacheReq(peek(req.addr), peek(req.data), peek(req.mask)))
////  val irespHandler = new ValidSource(c.io.icache.resp,
////    (resp: CacheResp, in: TestCacheResp) => reg_poke(resp.data, in.data))
////  val drespHandler = new ValidSource(c.io.dcache.resp,
////    (resp: CacheResp, in: TestCacheResp) => reg_poke(resp.data, in.data))
////  val mem = new CoreMem(ireqHandler.outputs, irespHandler.inputs,
////    dreqHandler.outputs, drespHandler.inputs, peek(c.io.dcache.abort),
////    4, verbose=args.verbose)
//
//  mem loadMem args.loadmem
//  preprocessors += mem
//  wire_poke(c.io.icache.resp.valid, true)
//  wire_poke(c.io.dcache.resp.valid, true)
//  ireqHandler.process()
//  dreqHandler.process()
//  mem.process()
//  irespHandler.process()
//  drespHandler.process()
//  if (!run(c.io.host, args.maxcycles)) fail
//}
//
//class TileMagicMem(
//    cmdQ: ScalaQueue[TestMemReq], dataQ: ScalaQueue[TestMemData], respQ: ScalaQueue[TestMemResp],
//    beats: Int, databits: Int, depth: Int = 1 << 20, verbose: Boolean = true)(
//    implicit logger: PrintStream) extends SimMem(beats*databits/8, depth, verbose)(logger) {
//  private val mask = (BigInt(1) << databits) - 1
//  def process {
//    if (!cmdQ.isEmpty && cmdQ.front.rw && dataQ.size >= beats) {
//      val cmd  = cmdQ.dequeue
//      write(cmd.addr, ((0 until beats) foldLeft BigInt(0))((data, i) =>
//        data | dataQ.dequeue.data << i*databits))
//    } else if (!cmdQ.isEmpty && !cmdQ.front.rw) {
//      val cmd  = cmdQ.dequeue
//      val data = read(cmd.addr)
//      (0 until beats) foreach (i => respQ enqueue
//        new TestMemResp((data >> i*databits) & mask, cmd.tag))
//    }
//  }
//}
//
//class NastiMagicMem(
//    arQ: ScalaQueue[TestNastiReadAddr],  rQ: ScalaQueue[TestNastiReadData],
//    awQ: ScalaQueue[TestNastiWriteAddr], wQ: ScalaQueue[TestNastiWriteData],
//    word_width: Int = 16, depth: Int = 1 << 20, verbose: Boolean = true)(
//    implicit logger: PrintStream) extends SimMem(word_width, depth, verbose)(logger) {
//  private var aw: Option[TestNastiWriteAddr] = None
//  def process = aw match {
//    case Some(p) if wQ.size > p.len =>
//      assert((1 << p.size) == word_width)
//      (0 to p.len) foreach (i =>
//        write((p.addr >> off) + i, wQ.dequeue.data))
//      aw = None
//    case None if !awQ.isEmpty => aw = Some(awQ.dequeue)
//    case None if !arQ.isEmpty =>
//      val ar = arQ.dequeue
//      (0 to ar.len) foreach (i =>
//        rQ enqueue new TestNastiReadData(
//          ar.id, read((ar.addr >> off) + i), i == ar.len))
//    case _ =>
//  }
//}
//
//class TileMem(
//    cmdQ: ScalaQueue[TestMemReq], dataQ: ScalaQueue[TestMemData], respQ: ScalaQueue[TestMemResp],
//    latency: Int, beats: Int, databits: Int, depth: Int = 1 << 20, verbose: Boolean = true)(
//    implicit logger: PrintStream) extends SimMem(beats*databits/8, depth, verbose)(logger) {
//  private val mask = (BigInt(1) << databits) - 1
//  private val schedule = Array.fill(latency){ScalaQueue[TestMemResp]()}
//  private var cur_cycle = 0
//  def process {
//    if (!cmdQ.isEmpty && cmdQ.front.rw && dataQ.size >= beats) {
//      val cmd = cmdQ.dequeue
//      write(cmd.addr, ((0 until beats) foldLeft BigInt(0))((data, i) =>
//        data | dataQ.dequeue.data << i*databits))
//    } else if (!cmdQ.isEmpty && !cmdQ.front.rw) {
//      val cmd = cmdQ.dequeue
//      val data = read(cmd.addr)
//      (0 until beats) foreach (i =>
//        schedule((cur_cycle+latency-1) % latency) enqueue
//          new TestMemResp((data >> i*databits) & mask, cmd.tag))
//    }
//    while (!schedule(cur_cycle).isEmpty) {
//      respQ enqueue schedule(cur_cycle).dequeue
//    }
//    cur_cycle = (cur_cycle + 1) % latency
//  }
//}
//
//class NastiMem(
//    arQ: ScalaQueue[TestNastiReadAddr],  rQ: ScalaQueue[TestNastiReadData],
//    awQ: ScalaQueue[TestNastiWriteAddr], wQ: ScalaQueue[TestNastiWriteData],
//    latency: Int, word_width: Int = 16, depth: Int = 1 << 20, verbose: Boolean = true)(
//    implicit logger: PrintStream) extends SimMem(word_width, depth, verbose)(logger) {
//  private var aw: Option[TestNastiWriteAddr] = None
//  private val schedule = Array.fill(latency){ScalaQueue[TestNastiReadData]()}
//  private var cur_cycle = 0
//  def process {
//    aw match {
//      case Some(p) if wQ.size > p.len =>
//        assert((1 << p.size) == word_width)
//        (0 to p.len) foreach (i =>
//          write((p.addr >> off) + i, wQ.dequeue.data))
//        aw = None
//      case None if !awQ.isEmpty => aw = Some(awQ.dequeue)
//      case None if !arQ.isEmpty =>
//        val ar = arQ.dequeue
//        (0 to ar.len) foreach (i =>
//          schedule((cur_cycle+latency-1) % latency) enqueue
//            new TestNastiReadData(ar.id, read((ar.addr >> off) + i), i == ar.len))
//      case _ =>
//    }
//    while (!schedule(cur_cycle).isEmpty) {
//      rQ enqueue schedule(cur_cycle).dequeue
//    }
//    cur_cycle = (cur_cycle + 1) % latency
//  }
//}
//
//class TileTester(c: Tile, args: MiniTestArgs)
//    extends AdvTester(c, 16, args.logFile) with MiniTests {
//  lazy val cmdHandler = new DecoupledSink(c.io.mem.req_cmd, (cmd: MemReqCmd) =>
//    new TestMemReq(peek(cmd.addr).toInt, peek(cmd.tag), peek(cmd.rw) != 0))
//  lazy val dataHandler = new DecoupledSink(c.io.mem.req_data, (data: MemData) =>
//    new TestMemData(peek(data.data)))
//  lazy val respHandler = new DecoupledSource(c.io.mem.resp, (resp: MemResp, in: TestMemResp) =>
//    {reg_poke(resp.data, in.data) ; reg_poke(resp.tag, in.tag)})
//  lazy val mem = new TileMem(
//    cmdHandler.outputs, dataHandler.outputs, respHandler.inputs,
//    args.memlatency, c.icache.mifDataBeats, c.icache.mifDataBits, verbose=args.verbose)
//
//  lazy val arHandler = new DecoupledSink(c.io.nasti.ar, (ar: NastiReadAddressChannel) =>
//    new TestNastiReadAddr(peek(ar.id), peek(ar.addr), peek(ar.size), peek(ar.len)))
//  lazy val awHandler = new DecoupledSink(c.io.nasti.aw, (aw: NastiWriteAddressChannel) =>
//    new TestNastiWriteAddr(peek(aw.id), peek(aw.addr), peek(aw.size), peek(aw.len)))
//  lazy val wHandler = new DecoupledSink(c.io.nasti.w, (w: NastiWriteDataChannel) =>
//    new TestNastiWriteData(peek(w.data), peek(w.last)))
//  lazy val rHandler = new DecoupledSource(c.io.nasti.r,
//    (r: NastiReadDataChannel, in: TestNastiReadData) =>
//      {reg_poke(r.id, in.id) ; reg_poke(r.data, in.data) ; reg_poke(r.last, in.last)})
//  lazy val nasti = new NastiMem(
//    arHandler.outputs, rHandler.inputs,
//    awHandler.outputs, wHandler.outputs,
//    args.memlatency, c.icache.nastiXDataBits/8, verbose=args.verbose)
//
//  if (c.core.useNasti) {
//    nasti loadMem args.loadmem
//    preprocessors += nasti
//  } else {
//    mem loadMem args.loadmem
//    preprocessors += mem
//  }
//  if (!run(c.io.host, args.maxcycles)) fail
//}
