package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import bus.simplebus._
import difftest.DifftestStoreEvent
import eulacore.mem.HasMemAccessMaster
import sim.DeviceSpace
import top.Settings

class UnpipeLSUIO extends FunctionUnitIO {
  val wdata = Input(UInt(XLEN.W))
  val instr = Input(UInt(32.W)) // Atom insts need aq rl funct3 bit from instr
  val dmem = new SimpleBusUC(addrBits = VAddrBits, userBits = dmmuUserBits)
  val isMMIO = Output(Bool())
  val storeCheck = new StoreCheckIO
  val pc = Input(UInt(XLEN.W))
  val la32rExcp = new La32rLSUExcpIO
}

abstract class AbstractUnpipelinedLSU(implicit val p: EulaCoreConfig) extends EulaCoreModule {
  val io = IO(new UnpipeLSUIO)
  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)

  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }
}

object La32rLSUOpType {
  // Op(1,0) = 00 : byte operation
  // Op(1,0) = 01 : half operation
  // Op(1,0) = 10 : word operation
  // Op(3) = 1 : unsigned load
  // Op(4) = 1 : load, 0 : store
  //
  def lb  = "b0010000".U
  def lh  = "b0010001".U
  def lw  = "b0010010".U
  def lbu = "b0011000".U
  def lhu = "b0011001".U
  def sb  = "b0000000".U
  def sh  = "b0000001".U
  def sw  = "b0000010".U
  def llw = "b1010010".U
  def scw = "b1000010".U

  def isLoad(func: UInt) = func(4)
  def isStore(func: UInt) = !func(4)
  def isUnsignedLoad(func: UInt) = func(4) && func(3)
  def isByteOp(func: UInt) = func(1, 0) === "b00".U
  def isHalfOp(func: UInt) = func(1, 0) === "b01".U
  def isWordOp(func: UInt) = func(1, 0) === "b10".U
}

// for load & store operation , vaddr = src1 + SignExt(src2(11, 0), 32)
// for store operation, use wdata as store data
class La32rUnpipelinedLSU(implicit override val p: EulaCoreConfig) extends AbstractUnpipelinedLSU with HasMemAccessMaster {

  def genWmask(addr: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> 0x1.U, //0001 << addr(1:0)
      "b01".U -> 0x3.U, //0011
      "b10".U -> 0xf.U //1111
    )) << addr(1, 0)
  }

  def genWdata(data: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> Fill(4, data(7, 0)),
      "b01".U -> Fill(2, data(15, 0)),
      "b10".U -> data
    ))
  }

  // because UnpipelinedLSU is a multi cycle unit, so we need "hold" input to ensure
  // input data does not change during processing
  val holdSrc1 = HoldUnless(src1, io.in.fire)
  val holdSrc2 = HoldUnless(src2, io.in.fire)
  val holdFunc = HoldUnless(func, io.in.fire)
  val holdWdata = HoldUnless(io.wdata, io.in.fire)
  val holdInstr = HoldUnless(io.instr, io.in.fire)

  val llscvaddr = holdSrc1 + SignExt(Cat(holdInstr(23 ,10), 0.U(2.W)), VAddrBits)
  val vaddr = Mux(holdFunc === La32rLSUOpType.llw || holdFunc === La32rLSUOpType.scw, llscvaddr, holdSrc1 + holdSrc2)
  val dmem = io.dmem
  val isStore = La32rLSUOpType.isStore(holdFunc)
  val partialLoad = La32rLSUOpType.isLoad(holdFunc) && (holdFunc =/= La32rLSUOpType.lw) && (holdFunc =/= La32rLSUOpType.llw)
  val addrAligned = WireInit(false.B)
  val unHoldAddrAligned = WireInit(false.B)
  val unHoldVaddr = Mux(func === La32rLSUOpType.llw || func === La32rLSUOpType.scw,
    src1 + SignExt(Cat(io.instr(23 ,10), 0.U(2.W)), VAddrBits), src1 + src2)

  // llbit
  val llbit = WireInit(false.B)
  val setllbit = WireInit(false.B)
  val clearllbit = WireInit(false.B)
  BoringUtils.addSink(llbit, "LLBIT")
  BoringUtils.addSource(setllbit, "SET_LLBIT")
  BoringUtils.addSource(clearllbit, "CLEAR_LLBIT")
  val scwAllow = (holdFunc =/= La32rLSUOpType.scw) || (holdFunc === La32rLSUOpType.scw && llbit === 1.U)
  val unHoldScwAllow = (func =/= La32rLSUOpType.scw) || (func === La32rLSUOpType.scw && llbit === 1.U)
  // ll.w set llbit
  setllbit := io.out.fire() && holdFunc === La32rLSUOpType.llw && !io.la32rExcp.hasExcp
  clearllbit := io.out.fire() && holdFunc === La32rLSUOpType.scw && !io.la32rExcp.hasExcp && llbit === 1.U

  val s_idle :: s_wait_resp :: s_partialLoad :: Nil = Enum(3)
  val state = RegInit(s_idle)

  switch(state) {
    is(s_idle) {
      when(dmem.req.fire) { state := s_wait_resp }
    }
    is(s_wait_resp) {
      when(dmem.resp.fire) { state := Mux(partialLoad, s_partialLoad, s_idle) }
    }
    is(s_partialLoad) { when (io.out.fire) { state := s_idle } }
  }

  val size = holdFunc(1, 0)

  val reqUserBits = Wire(new DmmuUserBundle)
  reqUserBits.isDeviceLoad := !isStore
  reqUserBits.memAccessMaster := Mux(isStore, STORE, LOAD)
  reqUserBits.tlbExcp := 0.U.asTypeOf(reqUserBits.tlbExcp)
  reqUserBits.paddr := 0.U
  reqUserBits.isInvalidPaddr := false.B
  reqUserBits.mat := 0.U

  dmem.req.bits.apply(
    addr = vaddr,
    size = size,
    wdata = genWdata(holdWdata, size),
    wmask = genWmask(vaddr, size),
    cmd = Mux(isStore, SimpleBusCmd.write, SimpleBusCmd.read),
    user = reqUserBits.asUInt())
  dmem.req.valid := valid && (state === s_idle) && addrAligned && scwAllow
  dmem.resp.ready := true.B //partialLoad || io.out.ready

  io.in.ready := (state === s_idle) && (dmem.req.ready || !unHoldAddrAligned || !unHoldScwAllow)
  io.out.valid := Mux(valid && (state === s_idle) && (!addrAligned || !scwAllow), true.B, Mux(partialLoad, state === s_partialLoad, dmem.resp.fire && (state === s_wait_resp)))

  val rdata = HoldUnless(dmem.resp.bits.rdata, dmem.resp.fire)
  val rdataDelay1 = RegNext(rdata)

  val rdataSel = LookupTree(vaddr(1, 0), List(
    "b00".U -> rdataDelay1(31, 0),
    "b01".U -> rdataDelay1(31, 8),
    "b10".U -> rdataDelay1(31, 16),
    "b11".U -> rdataDelay1(31, 24)
  ))

  val rdataPartialLoad = LookupTree(holdFunc, List(
    La32rLSUOpType.lb   -> SignExt(rdataSel(7, 0), XLEN),
    La32rLSUOpType.lh   -> SignExt(rdataSel(15, 0), XLEN),
    La32rLSUOpType.lw   -> SignExt(rdataSel(31, 0), XLEN),
    La32rLSUOpType.lbu  -> ZeroExt(rdataSel(7, 0), XLEN),
    La32rLSUOpType.lhu  -> ZeroExt(rdataSel(15, 0), XLEN)
  ))

  io.out.bits := Mux(holdFunc === La32rLSUOpType.scw, Cat(0.U(31.W), llbit),
    Mux(partialLoad, rdataPartialLoad, rdata(XLEN - 1, 0)))

  addrAligned := LookupTree(holdFunc(1, 0), List(
    "b00".U -> true.B,
    "b01".U -> (vaddr(0) === 0.U),
    "b10".U -> (vaddr(1, 0) === 0.U)
  ))

  unHoldAddrAligned := LookupTree(func(1, 0), List(
    "b00".U -> true.B,
    "b01".U -> (unHoldVaddr(0) === 0.U),
    "b10".U -> (unHoldVaddr(1, 0) === 0.U)
  ))

  val respUserBits = HoldUnless(dmem.resp.bits.user.get.asTypeOf(new DmmuUserBundle), dmem.resp.fire)

  io.la32rExcp.hasExcp := (io.la32rExcp.ale | respUserBits.tlbExcp.asUInt().orR) && io.out.valid
  io.la32rExcp.ale := io.out.fire && !addrAligned
  io.la32rExcp.badv := vaddr
  io.la32rExcp.tlbExcp := (respUserBits.tlbExcp.asUInt() & Fill(respUserBits.tlbExcp.getWidth, io.out.fire)).asTypeOf(respUserBits.tlbExcp)


  val isReadDevice = HoldReleaseLatch(valid=dmem.resp.valid && respUserBits.isDeviceLoad.asBool(), release=io.out.fire, flush=false.B)

  io.isMMIO := isReadDevice

  Debug(io.la32rExcp.ale, "misaligned addr detected\n")

  LADebug(dmem.req.fire, "[LSUREQ]pc=0x%x instr=0x%x vaddr=0x%x size=%d wdata=0x%x wmask=0x%x cmd=%d\n",
    io.pc, io.instr, vaddr, size, dmem.req.bits.wdata, dmem.req.bits.wmask, dmem.req.bits.cmd)

  LADebug(io.out.fire, "[LSURESP]pc=0x%x instr=0x%x rdata=0x%x\n", io.pc, io.instr, io.out.bits)


  // storeData format need align with la32r-nemu,(see NEMU/src/memory/paddr.c : store_commit_queue_push)
  io.storeCheck.valid := HoldReleaseLatch(valid=dmem.req.fire && isStore,release=io.out.fire, flush = false.B) && !respUserBits.isInvalidPaddr && !io.la32rExcp.hasExcp
  val offset = vaddr(1, 0)
  io.storeCheck.storeAddr := respUserBits.paddr
  io.storeCheck.storeData := Mux(La32rLSUOpType.isByteOp(holdFunc), (holdWdata & 0xff.U) << (offset << 3),
        Mux(La32rLSUOpType.isHalfOp(holdFunc), (holdWdata & 0xffff.U) << (offset << 3), holdWdata))

}
