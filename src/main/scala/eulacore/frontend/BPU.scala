package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import utils._
import top.Settings

class TableAddr(val idxBits: Int) extends EulaCoreBundle {
  val padLen = 2
  def tagBits = VAddrBits - padLen - idxBits

  //val res = UInt((AddrBits - VAddrBits).W)
  val tag = UInt(tagBits.W)
  val idx = UInt(idxBits.W)
  val pad = UInt(padLen.W)

  def fromUInt(x: UInt) = x.asTypeOf(UInt(VAddrBits.W)).asTypeOf(this)
  def getTag(x: UInt) = fromUInt(x).tag
  def getIdx(x: UInt) = fromUInt(x).idx
}

object BTBtype {
  def B = "b00".U  // branch
  def J = "b01".U  // jump
  def I = "b10".U  // indirect
  def R = "b11".U  // return

  def apply() = UInt(2.W)
}

class BPUUpdateReq extends EulaCoreBundle {
  val valid = Output(Bool())
  val pc = Output(UInt(VAddrBits.W))
  val isMissPredict = Output(Bool())
  val actualTarget = Output(UInt(VAddrBits.W))
  val actualTaken = Output(Bool())  // for branch
  val fuOpType = Output(FuOpType())
  val btbType = Output(BTBtype())
}

class BPU_embedded extends EulaCoreModule {
  val io = IO(new Bundle {
    val in = new Bundle { val pc = Flipped(Valid((UInt(32.W)))) }
    val out = new RedirectIO
    val flush = Input(Bool())
  })

  val flush = BoolStopWatch(io.flush, io.in.pc.valid, startHighPriority = true)

  // BTB
  val NRbtb = 512
  val btbAddr = new TableAddr(log2Up(NRbtb))
  def btbEntry() = new Bundle {
    val tag = UInt(btbAddr.tagBits.W)
    val _type = UInt(2.W)
    val target = UInt(32.W)
  }

  val btb = Module(new SRAMTemplate(btbEntry(), set = NRbtb, shouldReset = true, holdRead = true, singlePort = true))
  btb.io.r.req.valid := io.in.pc.valid
  btb.io.r.req.bits.setIdx := btbAddr.getIdx(io.in.pc.bits)

  val btbRead = Wire(btbEntry())
  btbRead := btb.io.r.resp.data(0)
  // since there is one cycle latency to read SyncReadMem,
  // we should latch the input pc for one cycle
  val pcLatch = RegEnable(next = io.in.pc.bits, enable = io.in.pc.valid, init = 0.U)
  val btbHit = btbRead.tag === btbAddr.getTag(pcLatch) && !flush && RegNext(btb.io.r.req.ready, init = false.B)

  // PHT
  val pht = Module(new SRAMTemplateWithArbiter(2, UInt(2.W), set = NRbtb, shouldReset = true))
  pht.io.r(1).req.valid := io.in.pc.valid
  pht.io.r(1).req.bits.setIdx := btbAddr.getIdx(io.in.pc.bits)
  val phtTaken = pht.io.r(1).resp.data(0)(1) && RegNext(pht.io.r(1).req.fire, init = false.B)

  // RAS
  val NRras = 16
  val ras = Module(new SRAMTemplate(UInt(32.W), set = NRras, shouldReset = true, holdRead = true, singlePort = true))
  val sp = Counter(NRras)
  ras.io.r.req.valid := io.in.pc.valid
  ras.io.r.req.bits.setIdx := sp.value
  val rasTarget = ras.io.r.resp.data(0)

  // update
  val req = WireInit(0.U.asTypeOf(new BPUUpdateReq))
  val btbWrite = WireInit(0.U.asTypeOf(btbEntry()))
  BoringUtils.addSink(req, "bpuUpdateReq")

  btbWrite.tag := btbAddr.getTag(req.pc)
  btbWrite.target := req.actualTarget
  btbWrite._type := req.btbType
  // NOTE: We only update BTB at a miss prediction.
  // If a miss prediction is found, the pipeline will be flushed
  // in the next cycle. Therefore it is safe to use single-port
  // SRAM to implement BTB, since write requests have higher priority
  // than read request. Again, since the pipeline will be flushed
  // in the next cycle, the read request will be useless.
  btb.io.w.req.valid := req.isMissPredict && req.valid
  btb.io.w.req.bits.setIdx := btbAddr.getIdx(req.pc)
  btb.io.w.req.bits.data := btbWrite

  pht.io.r(0).req.valid := req.valid
  pht.io.r(0).req.bits.setIdx := btbAddr.getIdx(req.pc)
  val cnt = pht.io.r(0).resp.data(0)
  val reqLatch = RegNext(req)

  val taken = reqLatch.actualTaken
  val newCnt = Mux(taken, cnt + 1.U, cnt - 1.U)
  val phtwen = ((taken && (cnt =/= "b11".U)) || (!taken && (cnt =/= "b00".U))) && reqLatch.valid && La32rALUOpType.isBranch(reqLatch.fuOpType)
  pht.io.w.req.valid := phtwen
  pht.io.w.req.bits.setIdx := btbAddr.getIdx(reqLatch.pc)
  pht.io.w.req.bits.data := newCnt

  ras.io.w.req.valid := req.valid && (req.fuOpType === La32rALUOpType.call)
  ras.io.w.req.bits.setIdx := sp.value + 1.U
  ras.io.w.req.bits.data := req.pc + 4.U

  when (req.valid) {
    when (req.fuOpType === La32rALUOpType.call) {
      sp.value := sp.value + 1.U
    }
    .elsewhen (req.fuOpType === La32rALUOpType.ret) {
      sp.value := sp.value - 1.U
    }
  }


  val bpuOutRawTarget = Mux(btbRead._type === BTBtype.R, rasTarget, btbRead.target)

    io.out.target := Cat(bpuOutRawTarget(XLEN - 1, 2), 0.U(2.W))

  io.out.valid := btbHit && Mux(btbRead._type === BTBtype.B, phtTaken, true.B)
  io.out.rtype := 0.U
}