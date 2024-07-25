package eulacore

import bus.simplebus.{SimpleBusCmd, SimpleBusUC}
import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import top.Settings
import difftest._
import eulacore.mem.{HasLa32rTLBConst, HasMemAccessMaster, La32rMMU, La32rMMUConfig, La32rTLB}


class CSRIO extends FunctionUnitIO {
  val cfIn = Flipped(new CtrlFlowIO)
  val redirect = new RedirectIO
  // for exception check
  val instrValid = Input(Bool())
  val isBackendException = Input(Bool())
  // for differential testing
  val intrNO = Output(UInt(XLEN.W))
  val wenFix = Output(Bool())
  val la32rLSUExcp = Flipped(new La32rLSUExcpIO)
  val difftestExceptionSkip = Output(Bool()) // for interrupt difftest align
  val tlbModifyInst = Output(Bool())
}

class AbstractCSR(implicit val p: EulaCoreConfig) extends EulaCoreModule{
  val io = IO(new CSRIO)

  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)

  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }
}

object La32rCSROpType {
  def csracc = 1.U // general name for csrrd, csrwr, csrxchg
  def csrrd = 2.U
  def csrwr = 3.U // src2 is rd
  def csrxchg = 4.U // src1 is rj, src2 is rd
  def rdcntvl = 5.U
  def rdcntvh = 6.U
  def rdcntid = 7.U
  def excption = 8.U // interrupt is equal to excption
  def break = 9.U
  def syscall = 10.U
  def ertn = 11.U
  def cacop = 12.U
  def tlbsrch = 13.U
  def tlbrd = 14.U
  def tlbwr = 15.U
  def tlbfill = 16.U
  def invtlb = 17.U // src1 = rj ,src2 = rk
  def idle = 18.U
  def preld = 19.U

}


trait HasLa32rCSRConst {
  // CSR ADDR
  val CRMDaddr      = 0x0
  val PRMDaddr      = 0x1
  val EUENaddr      = 0X2
  val ECFGaddr      = 0x4
  val ESTATaddr     = 0x5
  val ERAaddr       = 0x6
  val BADVaddr      = 0x7
  val EENTRYaddr    = 0xc
  val TLBIDXaddr    = 0x10
  val TLBEHIaddr    = 0x11
  val TLBELO0addr   = 0x12
  val TLBELO1addr   = 0x13
  val ASIDaddr      = 0x18
  val PGDLaddr      = 0x19
  val PGDHaddr      = 0x1A
  val PGDaddr       = 0x1B
  val CPUIDaddr     = 0x20
  val SAVE0addr     = 0x30
  val SAVE1addr     = 0x31
  val SAVE2addr     = 0x32
  val SAVE3addr     = 0X33
  val TIDaddr       = 0x40
  val TCFGaddr      = 0x41
  val TVALaddr      = 0x42
  val TICLRaddr     = 0x44
  val LLBCTLaddr    = 0x60
  val TLBRENTRYaddr = 0x88
  val DMW0addr      = 0x180
  val DMW1addr      = 0x181

  // CSR write mask for CSR instruction
  val CRMDWmask     = "b11_11_1_1_1_11".U(32.W)
  val PRMDWmask     = "b1_11".U(32.W)
  val EUENWmask     = 0.U(32.W) // floating point instructions are not implemented, so FPE is always zero
  val ECFGWmask     = "b11_0_1111111111".U(32.W)
  val ESTATWmask    = "b11".U(32.W)
  val EENTRYWmask   = 0xFFFFFFC0.S(32.W).asUInt
  val CPUIDWmask    = 0.U(32.W)
  val LLBCTLWmask   = "b100".U(32.W) // WCLLB is not write visible to align with nemu
  val TLBIDXWmask   = "b1_0_111111_00000000_0000000000000000".U(32.W).asUInt() | Fill(log2Up(Settings.getInt("TlbEntryNum")), true.B)
//  val TLBIDXWmask   = "b1_0_111111_00000000_1111111111111111".U(32.W).asUInt
  val TLBEHIWmask   = 0xFFFFE000.S(32.W).asUInt
  val TLBELO0Wmask  = 0xFFFFF7F.S(32.W).asUInt // we assume PALEN = 32
  val TLBELO1Wmask  = 0xFFFFF7F.S(32.W).asUInt
  val ASIDWmask     = 0x3FF.U(32.W)
  val PGDLWmask     = 0xFFFFF000.S(32.W).asUInt
  val PGDHWmask     = 0xFFFFF000.S(32.W).asUInt
  val PGDWmask      = 0.U(32.W)
  val TLBRENTRYWmask = 0xFFFFFFC0.S(32.W).asUInt
  val DMW0Wmask     = "b111_0_111_0000000000000000000_11_1_00_1".U(32.W).asUInt
  val DMW1Wmask     = "b111_0_111_0000000000000000000_11_1_00_1".U(32.W).asUInt
  val TICLRWmask    = 1.U(32.W)

  val timerWidth = 32

  val CoherentCached = 1.U
  val StronglyOrderedUncached = 0.U

  // csr struct define
  class CRMDStruct extends Bundle {
    val pad = Output(UInt(23.W))
    val DATM = Output(UInt(2.W))
    val DATF = Output(UInt(2.W))
    val PG = Output(UInt(1.W))
    val DA = Output(UInt(1.W))
    val IE = Output(UInt(1.W))
    val PLV = Output(UInt(2.W))
  }

  class PRMDStruct extends Bundle {
    val pad = Output(UInt(29.W))
    val PIE = Output(UInt(1.W))
    val PPLV = Output(UInt(2.W))
  }

  class TCFGStruct extends Bundle {
    val pad = if (timerWidth < 32) Output(UInt((32 - timerWidth).W)) else null
    val InitVal = Output(UInt(timerWidth.W))
    val Periodic = Output(UInt(1.W))
    val En = Output(UInt(1.W))
  }

  class ESTATStruct extends Bundle {
    val pad3 = Output(UInt(1.W))
    val EsubCode = Output(UInt(9.W))
    val Ecode = Output(UInt(6.W))
    val pad2 = Output(UInt(3.W))
    val IPI = Output(UInt(1.W))
    val TI = Output(UInt(1.W))
    val pad1 = Output(UInt(1.W))
    val HWI = Output(UInt(8.W))
    val SWI = Output(UInt(2.W))
  }

  class LLBCTLStruct extends Bundle {
    val pad = Output(UInt(29.W))
    val KLO = Output(UInt(1.W))
    val WCLLB = Output(UInt(1.W))
    val ROLLB = Output(UInt(1.W))
  }

  class DMWStruct extends Bundle {
    val VSEG = Output(UInt(3.W))
    val pad0 = Output(UInt(1.W))
    val PSEG = Output(UInt(3.W))
    val pad1 = Output(UInt(19.W))
    val MAT = Output(UInt(2.W))
    val PLV3 = Output(UInt(1.W))
    val pad2 = Output(UInt(2.W))
    val PLV0 = Output(UInt(1.W))
  }

  class ASIDStruct extends Bundle {
    val pad0 = Output(UInt(8.W))
    val ASIDBITS = Output(UInt(8.W))
    val pad1 = Output(UInt(6.W))
    val ASID = Output(UInt(10.W))
  }

  class TLBEHIStruct extends Bundle {
    val VPPN = Output(UInt(19.W))
    val pad = Output(UInt(13.W))
  }

  class TLBELOStruct extends Bundle {
    val pad0 = Output(UInt(4.W))
    val PPN = Output(UInt(20.W))
    val pad1 = Output(UInt(1.W))
    val G = Output(UInt(1.W))
    val MAT = Output(UInt(2.W))
    val PLV = Output(UInt(2.W))
    val D = Output(UInt(1.W))
    val V = Output(UInt(1.W))
  }

  class TLBIDXStruct extends Bundle {
    val NE = Output(UInt(1.W))
    val pad0 = Output(UInt(1.W))
    val PS = Output(UInt(6.W))
    val pad1 = Output(UInt(8.W))
    val pad2 = Output(UInt((16 - log2Up(Settings.getInt("TlbEntryNum"))).W))
    val index = Output(UInt(log2Up(Settings.getInt("TlbEntryNum")).W))
  }

}

// TODO : split fetch tlb excp and set fetch tlb excp number less than decode excp
trait HasLa32rExceptionNO {
  // do not change this encode because it represents priority
  def INT = 0
  // fetch
  def ADEF = 1
  def PIF = 2
  // decode
  def INE = 3
  def IPE = 4
  def FPD = 5
  // execute
  def ALE = 6
  def SYS = 7
  def BRK = 8
  def TLBR = 9
  def PIL = 10
  def PIS = 11
  def PPI = 12
  def PME = 13
  def FPE = 14

//  val ExcPriority = Seq(
//    INT,
//    // fetch
//    ADEF,
//    PIF,
//    // decode
//    INE,
//    IPE,
//    FPD,
//    // execute
//    ALE,
//    SYS,
//    BRK,
//    TLBR,
//    PIL,
//    PIS,
//    PPI,
//    PME,
//    FPE
//  )


}

class La32rCSR(implicit override val p: EulaCoreConfig) extends AbstractCSR with HasLa32rCSRConst with HasLa32rTLBConst with HasMemAccessMaster {
  assert(XLEN == 32)

  // reg define
  val EUEN, ECFG, ERA, BADV, EENTRY, PGDL, PGDH, CPUID,
  SAVE0, SAVE1, SAVE2, SAVE3, TID, TCFG, TVAL, TICLR, TLBRENTRY, DMW0, DMW1 = RegInit(UInt(XLEN.W), 0.U)
  val CRMD = RegInit(8.U.asTypeOf(new CRMDStruct)) // when reset, DA = 1
  val PRMD = RegInit(0.U.asTypeOf(new PRMDStruct))
  val ASID = RegInit(0xA0000.U.asTypeOf(new ASIDStruct)) // we need set ASIDBITS=10, see spec 7.5.4
  val ESTAT = RegInit(0.U.asTypeOf(new ESTATStruct))
  val PGD = Mux(BADV(31), PGDH, PGDL) // see spec 7.5.7
  val LLBCTL = RegInit(0.U.asTypeOf(new LLBCTLStruct))
  val TLBIDX = RegInit(0.U.asTypeOf(new TLBIDXStruct))
  val TLBEHI = RegInit(0.U.asTypeOf(new TLBEHIStruct))
  val TLBELO0 = RegInit(0.U.asTypeOf(new TLBELOStruct))
  val TLBELO1 = RegInit(0.U.asTypeOf(new TLBELOStruct))

  val raiseException = WireInit(false.B)

  // perfcnt
  val hasPerfCnt = EnablePerfCnt && !p.FPGAPlatform
  val nrPerfCnts = if (hasPerfCnt) 0x80 else 0x3
  val perfCnts = List.fill(nrPerfCnts)(RegInit(0.U(64.W)))
  val perfCntsLoMapping = (0 until nrPerfCnts).map { case i => MaskedRegMap(0xb00 + i, perfCnts(i)) }

  val mapping = Map(
    MaskedRegMap(CRMDaddr, CRMD.asUInt, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(PRMDaddr, PRMD.asUInt, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(EUENaddr, EUEN, EUENWmask),
    MaskedRegMap(ECFGaddr, ECFG, ECFGWmask),
    MaskedRegMap(ESTATaddr, ESTAT.asUInt, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(ERAaddr, ERA),
    MaskedRegMap(BADVaddr, BADV),
    MaskedRegMap(EENTRYaddr, EENTRY, EENTRYWmask),
    MaskedRegMap(TLBIDXaddr, TLBIDX.asUInt(), MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(TLBEHIaddr, TLBEHI.asUInt(), MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(TLBELO0addr, TLBELO0.asUInt(), MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(TLBELO1addr, TLBELO1.asUInt(), MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(ASIDaddr, ASID.asUInt(), MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(PGDLaddr, PGDL, PGDLWmask),
    MaskedRegMap(PGDHaddr, PGDH, PGDHWmask),
    MaskedRegMap(PGDaddr, PGD, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(CPUIDaddr, CPUID, CPUIDWmask),
    MaskedRegMap(SAVE0addr, SAVE0),
    MaskedRegMap(SAVE1addr, SAVE1),
    MaskedRegMap(SAVE2addr, SAVE2),
    MaskedRegMap(SAVE3addr, SAVE3),
    MaskedRegMap(TIDaddr, TID),
    MaskedRegMap(TCFGaddr, TCFG),
    MaskedRegMap(TVALaddr, TVAL, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(TICLRaddr, TICLR, TICLRWmask, MaskedRegMap.NoSideEffect, MaskedRegMap.UnwritableMask),
    MaskedRegMap(LLBCTLaddr, LLBCTL.asUInt, MaskedRegMap.UnwritableMask, null),
    MaskedRegMap(TLBRENTRYaddr, TLBRENTRY, TLBRENTRYWmask),
    MaskedRegMap(DMW0addr, DMW0, DMW0Wmask),
    MaskedRegMap(DMW1addr, DMW1, DMW1Wmask),
  ) ++ perfCntsLoMapping

  val tcfgStruct = TCFG.asTypeOf(new TCFGStruct)

  // stable counter
  val stableCounter = RegInit(UInt(64.W), 0.U)
  stableCounter := stableCounter + 1.U

  // handle csr access inst
  val addr = io.cfIn.instr(23, 10)
  val rdata = Wire(UInt(XLEN.W))
  val wdata = Mux(func === La32rCSROpType.csrxchg, MaskData(rdata, src2, src1) /*(src1 & src2) | (rdata & (~src1).asUInt())*/, src2)
  val wen = valid && (func === La32rCSROpType.csrxchg || func === La32rCSROpType.csrwr) && !io.isBackendException
  MaskedRegMap.generate(mapping, addr, rdata, wen, wdata)

  io.out.bits := LookupTreeDefault(func, rdata, List(
    La32rCSROpType.rdcntid -> TID,
    La32rCSROpType.rdcntvl -> stableCounter(31, 0),
    La32rCSROpType.rdcntvh -> stableCounter(63, 32)
  ))

  // fix csr reg software write
  when(wen && addr === CRMDaddr.U) {
    CRMD := MaskData(CRMD.asUInt(), wdata, CRMDWmask).asTypeOf(CRMD)
  }
  when(wen && addr === PRMDaddr.U) {
    PRMD := MaskData(PRMD.asUInt(), wdata, PRMDWmask).asTypeOf(PRMD)
  }
  when(wen && addr === LLBCTLaddr.U) {
    LLBCTL := MaskData(LLBCTL.asUInt(),wdata, LLBCTLWmask).asTypeOf(LLBCTL)
  }
  when(wen && addr === TLBIDXaddr.U) {
    TLBIDX := MaskData(TLBIDX.asUInt(), wdata, TLBIDXWmask).asTypeOf(TLBIDX)
  }
  when(wen && addr === TLBEHIaddr.U) {
    TLBEHI := MaskData(TLBEHI.asUInt(), wdata, TLBEHIWmask).asTypeOf(TLBEHI)
  }
  when(wen && addr === TLBELO0addr.U) {
    TLBELO0 := MaskData(TLBELO0.asUInt(), wdata, TLBELO0Wmask).asTypeOf(TLBELO0)
  }
  when(wen && addr === TLBELO1addr.U) {
    TLBELO1 := MaskData(TLBELO1.asUInt(), wdata, TLBELO1Wmask).asTypeOf(TLBELO1)
  }
  when(wen && addr === ASIDaddr.U) {
    ASID := MaskData(ASID.asUInt(), wdata, ASIDWmask).asTypeOf(ASID)
  }

  // timer(TVAL)  , see chiplab/IP/myCPU/csr.v
  val timer_en = RegInit(false.B)
  val timerInterrupt = ESTAT.TI

  when(timer_en && (TVAL === 0.U)) {
    timerInterrupt := true.B
    timer_en := tcfgStruct.Periodic
  }

  when(wen && addr === TCFGaddr.U) {
    timer_en := wdata(0)
  }

  when (timer_en) {
    when(TVAL =/= 0.U) {
      TVAL := TVAL - 1.U
    }
    when(TVAL === 0.U) {
      TVAL := Mux(tcfgStruct.Periodic.asBool(), Cat(tcfgStruct.InitVal, 0.U(2.W)), Fill(32, true.B))
    }
  }

  when(wen && addr === TCFGaddr.U) {
    TVAL := Cat(src2(31, 2), 0.U(2.W))
  }

  val clearTimerInterrupt = wen && addr === TICLRaddr.U && wdata(0) === 1.U
  when(clearTimerInterrupt) {
    timerInterrupt := false.B
  }

  // LLBit ctrl
  val setllbit = WireInit(false.B)
  val clearllbit = WireInit(false.B)

  when (wen && addr === LLBCTLaddr.U && wdata(1) === 1.U) {
    LLBCTL.ROLLB := 0.U
  }

  when (setllbit) {
    LLBCTL.ROLLB := 1.U
  }

  when (clearllbit) {
    LLBCTL.ROLLB := 0.U
  }

  BoringUtils.addSink(setllbit, "SET_LLBIT")
  BoringUtils.addSink(clearllbit, "CLEAR_LLBIT")
  BoringUtils.addSource(LLBCTL.ROLLB, "LLBIT")


  // cacop
  val cacopCode = io.cfIn.instr(4, 0)
  val isCacop = valid && (func === La32rCSROpType.cacop)
  val cacopVA = SignExt(io.cfIn.instr(21, 10), XLEN) + src1
  val cacopPA = WireInit(0.U(PAddrBits.W))
  val isHitCacop = isCacop && (cacopCode(4, 3) === 2.U)
  val isIndexCacop = isCacop && (cacopCode(4, 3) === 1.U)
  val isStoreTagCacop = isCacop && (cacopCode(4, 3) === 0.U)

  BoringUtils.addSource(cacopCode, "CACOP_CODE")
  BoringUtils.addSource(isCacop, "CACOP_VALID")
  BoringUtils.addSource(cacopVA, "CACOP_VA")
  BoringUtils.addSource(cacopPA, "CACOP_PA")

  val cacopUserBits = Wire(new ImmuUserBundle)
  val cacopmmuIn = Wire(new SimpleBusUC(userBits = immuUserBits, addrBits = VAddrBits))
  val cacopmmu = La32rMMU(in = cacopmmuIn, enable = true)(La32rMMUConfig(name = "immu", userBits = immuUserBits, tlbEntryNum = Settings.getInt("TlbEntryNum"), FPGAPlatform = p.FPGAPlatform))

  cacopUserBits.pc := 0.U
  cacopUserBits.npc := 0.U
  cacopUserBits.brIdx := 0.U
  cacopUserBits.memAccessMaster := LOAD
  cacopUserBits.tlbExcp := 0.U.asTypeOf(cacopUserBits.tlbExcp)
  cacopUserBits.mat := 0.U

  cacopmmuIn.req.valid := isHitCacop
  cacopmmuIn.req.bits.apply(addr = cacopVA, size = "b00".U, cmd=SimpleBusCmd.read, wdata = 0.U, wmask=0.U, user = cacopUserBits.asUInt())
  cacopmmuIn.resp.ready := true.B

  cacopmmu.io.out.req.ready := true.B
  cacopmmu.io.out.resp.valid := false.B
  cacopmmu.io.out.resp.bits.rdata := 0.U
  cacopmmu.io.out.resp.bits.cmd := 0.U
  cacopmmu.io.out.resp.bits.user.foreach(_ := 0.U)

  cacopPA := cacopmmu.io.out.req.bits.addr
  val hitCacopTlbExcpTmp = cacopmmu.io.out.req.bits.user.get.asTypeOf(new ImmuUserBundle).tlbExcp
  val hitCacopTlbExcp = (hitCacopTlbExcpTmp.asUInt() & Fill(hitCacopTlbExcpTmp.asUInt().getWidth, isHitCacop)).asTypeOf(hitCacopTlbExcpTmp)

  val cacop_idle :: cacop_do :: cacop_done :: Nil = Enum(3)
  val cacop_state = RegInit(cacop_idle)
  val dcacheFlushDone = WireInit(false.B)
  val icacheFlushDone = WireInit(false.B)
  BoringUtils.addSink(dcacheFlushDone, "DCACHE_FLUSH_DONE")
  BoringUtils.addSink(icacheFlushDone, "ICACHE_FLUSH_DONE")
  switch (cacop_state) {
    is (cacop_idle) {
      when (isCacop && !raiseException) { cacop_state := cacop_do }
    }
    is (cacop_do) {
      when (icacheFlushDone || dcacheFlushDone) { cacop_state := cacop_done }
    }
    is (cacop_done) {
      when (io.out.fire) { cacop_state := cacop_idle }
    }
  }

  // Exception & interrupt

  // interrupts
  val ipi = WireInit(false.B)
  val hwi = WireInit(0.U(8.W))
  BoringUtils.addSink(ipi, "ipi")
  BoringUtils.addSink(hwi, "hwi")

  when(wen && addr === ESTATaddr.U) {
    ESTAT.SWI := wdata(1, 0)
  }
  ESTAT.HWI := hwi
  ESTAT.IPI := ipi

  val ESTATWire = WireInit(ESTAT.asUInt())
  val intrVec = (ESTATWire(12, 0) & ECFG(12, 0)) & Fill(13, CRMD.IE)
  BoringUtils.addSource(intrVec, "intrVecIDU")

  // exceptions
  val csrExceptionVec = Wire(Vec(16, Bool()))
  csrExceptionVec.foreach(_ := false.B)
  csrExceptionVec(SYS) := valid && func === La32rCSROpType.syscall
  csrExceptionVec(BRK) := valid && func === La32rCSROpType.break
  csrExceptionVec(ALE) := io.la32rLSUExcp.ale
  val isPrivInst = valid && (func === La32rCSROpType.csrrd || func === La32rCSROpType.csrwr ||
    func === La32rCSROpType.csrxchg || (func === La32rCSROpType.cacop && (cacopCode === 8.U || cacopCode === 9.U)) ||
    func === La32rCSROpType.tlbsrch || func === La32rCSROpType.tlbrd || func === La32rCSROpType.tlbwr ||
    func === La32rCSROpType.tlbfill || func === La32rCSROpType.invtlb || func === La32rCSROpType.ertn
    || func === La32rCSROpType.idle)
  csrExceptionVec(IPE) := isPrivInst && CRMD.PLV === 3.U
  csrExceptionVec(TLBR) := io.la32rLSUExcp.tlbExcp.tlbRefillExcp || hitCacopTlbExcp.tlbRefillExcp
  csrExceptionVec(PIL) := io.la32rLSUExcp.tlbExcp.loadPageInvalidExcp || hitCacopTlbExcp.loadPageInvalidExcp
  csrExceptionVec(PIS) := io.la32rLSUExcp.tlbExcp.storePageInvalidExcp
  csrExceptionVec(PPI) := io.la32rLSUExcp.tlbExcp.pagePrivInvalidExcp || hitCacopTlbExcp.pagePrivInvalidExcp
  csrExceptionVec(PME) := io.la32rLSUExcp.tlbExcp.pageModifyExcp || hitCacopTlbExcp.pageModifyExcp
  csrExceptionVec(INE) := valid && func === La32rCSROpType.invtlb && io.cfIn.instr(4, 0) > 6.U

  val hasFetchTlbExcp = io.instrValid && (io.cfIn.exceptionVec(TLBR) | io.cfIn.exceptionVec(PIF) | io.cfIn.exceptionVec(PPI))
  // when fetch has tlb excp, should not generate decode or exec excp because tlb excp NO is less than decode or exec excp NO
  val exceptionVec = (io.cfIn.exceptionVec.asUInt & Fill(16, io.instrValid)) | (csrExceptionVec.asUInt & Fill(16, !hasFetchTlbExcp))
  raiseException := exceptionVec.orR
  val excptionNO = PriorityEncoder(exceptionVec)
  io.wenFix := raiseException // TODO : what is this
  io.intrNO := excptionNO

  val Ecode = LookupTree(excptionNO, List(
    INT.asUInt -> 0x0.U,
    PIL.asUInt -> 0x1.U,
    PIS.asUInt -> 0x2.U,
    PIF.asUInt -> 0x3.U,
    PME.asUInt -> 0x4.U,
    PPI.asUInt -> 0x7.U,
    ADEF.asUInt -> 0x8.U,
    ALE.asUInt -> 0x9.U,
    SYS.asUInt -> 0xB.U,
    BRK.asUInt -> 0xC.U,
    INE.asUInt -> 0xD.U,
    IPE.asUInt -> 0xE.U,
    FPD.asUInt -> 0xF.U,
    FPE.asUInt -> 0x12.U,
    TLBR.asUInt -> 0x3F.U,
  ))

  val badvWrite = WireInit(0.U(32.W))

  when(raiseException) {
    PRMD.PPLV := CRMD.PLV
    PRMD.PIE := CRMD.IE
    CRMD.PLV := 0.U
    CRMD.IE := 0.U
    ERA := io.cfIn.pc
    ESTAT.Ecode := Ecode

    when (excptionNO === TLBR.U) {
      CRMD.DA := 1.U
      CRMD.PG := 0.U
    }

    when ((excptionNO === ADEF.U) || (excptionNO === TLBR.U && io.cfIn.exceptionVec(TLBR)) ||
      (excptionNO === PIF.U) || (excptionNO === PPI.U && io.cfIn.exceptionVec(PPI))) {
      badvWrite := io.cfIn.pc
      BADV := badvWrite
    }.elsewhen((excptionNO === TLBR.U) || (excptionNO === ALE.U) || (excptionNO === PIL.U) || (excptionNO === PIS.U)
      || (excptionNO === PME.U) || (excptionNO === PPI.U)) {
      badvWrite := Mux(isHitCacop, cacopVA, io.la32rLSUExcp.badv)
      BADV := badvWrite
    }

    when((excptionNO === TLBR.U) || (excptionNO === PIL.U) || (excptionNO === PIS.U) || (excptionNO === PIF.U) ||
      (excptionNO === PME.U) || (excptionNO === PPI.U)) {
      TLBEHI := Cat(badvWrite(31, 13), 0.U(13.W)).asTypeOf(TLBEHI)
    }
  }


  // ertn
  val retFromExcp = valid && func === La32rCSROpType.ertn
  when (retFromExcp) {
    CRMD.PLV := PRMD.PPLV
    CRMD.IE := PRMD.PIE
    when(ESTAT.Ecode === 0x3F.U) {
      CRMD.DA := 0.U
      CRMD.PG := 1.U
    }
    LLBCTL.KLO := 0.U
    when (LLBCTL.KLO === 0.U) {
      LLBCTL.ROLLB := 0.U // ROLLB is LLBit
    }
  }

  val exceptionEntry = Mux(excptionNO === TLBR.U, TLBRENTRY, EENTRY)

  // idle
  val isIdle = valid && func === La32rCSROpType.idle && CRMD.PLV === 0.U
  val isInIdle = RegInit(false.B)
  val idlePC = RegInit(0.U(XLEN.W))

  when(isIdle) {
    isInIdle := true.B
    idlePC := io.cfIn.pc
  }

  when(raiseException && excptionNO === INT.U && isInIdle) {
    isInIdle := false.B
    ERA := idlePC + 4.U
  }

  // preld
  val isPreld = valid && func === La32rCSROpType.preld
  when (isPreld) {
    printf("detected preld instr at pc=0x%x\n", io.cfIn.pc)
  }

  // mmu
  BoringUtils.addSource(CRMD.asUInt(), "CRMD")
  BoringUtils.addSource(DMW0.asUInt(), "DMW0")
  BoringUtils.addSource(DMW1.asUInt(), "DMW1")
  BoringUtils.addSource(ASID.asUInt(), "ASID")

  // tlb
  val tlbModifyValid = WireInit(false.B)
  val tlbModifyOp = WireInit(0.U(7.W))
  val invtlbOp = io.cfIn.instr(4, 0)
  val invtlbasid = src1(9, 0)
  val invtlbvaddr = src2
  BoringUtils.addSource(tlbModifyValid, "tlbModifyValid")
  BoringUtils.addSource(tlbModifyOp, "tlbModifyOp")
  BoringUtils.addSource(invtlbOp, "invtlbOp")
  BoringUtils.addSource(invtlbasid, "invtlbasid")
  BoringUtils.addSource(invtlbvaddr, "invtlbvaddr")
  BoringUtils.addSource(TLBEHI.asUInt(), "TLBEHI")
  BoringUtils.addSource(TLBELO0.asUInt(), "TLBELO0")
  BoringUtils.addSource(TLBELO1.asUInt(), "TLBELO1")
  BoringUtils.addSource(TLBIDX.asUInt(), "TLBIDX")
  BoringUtils.addSource(ESTAT.asUInt(), "ESTAT")
  BoringUtils.addSource(io.cfIn.pc, "tlbDebugPC")

  tlbModifyValid := valid && (func === La32rCSROpType.tlbwr || func === La32rCSROpType.tlbfill || func === La32rCSROpType.invtlb)
  tlbModifyOp := func

  val csrtlb = La32rTLB()(La32rMMUConfig(name = "csr", tlbEntryNum = Settings.getInt("TlbEntryNum"),FPGAPlatform = p.FPGAPlatform ))
  csrtlb.io.in.valid := valid && (func === La32rCSROpType.tlbsrch || func === La32rCSROpType.tlbrd)
  csrtlb.io.in.bits.vaddr := TLBEHI.asUInt()
  csrtlb.io.in.bits.memAccessMaster := 0.U
  val tlbextraread = csrtlb.io.csrread.get
  tlbextraread.readIdx := TLBIDX.asTypeOf(new TLBIDXStruct).index

  when (valid && func === La32rCSROpType.tlbsrch) {
    when (tlbextraread.hit) {
      TLBIDX.index := tlbextraread.hitIdx
      TLBIDX.NE := 0.U
    }.otherwise {
      TLBIDX.NE := 1.U
    }
  }

  when (valid && func === La32rCSROpType.tlbrd) {
    val readtlbhi = tlbextraread.readTlbHi.asTypeOf(new TLBHIStruct)
    val readtlblo0 = tlbextraread.readTlbLo0.asTypeOf(new TLBLOStruct)
    val readtlblo1 = tlbextraread.readTlbLo1.asTypeOf(new TLBLOStruct)
    val readtlbloVec = Seq(readtlblo0, readtlblo1)
    val TLBELOVec = Seq(TLBELO0, TLBELO1)
    when(readtlbhi.e === 1.U) {
      TLBEHI.VPPN := readtlbhi.vppn
      TLBELOVec.zip(readtlbloVec).foreach { case (tlbelo, readtlblo) =>
        tlbelo.V := readtlblo.v
        tlbelo.D := readtlblo.d
        tlbelo.PLV := readtlblo.plv
        tlbelo.MAT := readtlblo.mat
        tlbelo.G := readtlbhi.g
        tlbelo.PPN := readtlblo.ppn
      }
      TLBIDX.PS := readtlbhi.ps
      TLBIDX.NE := 0.U
      ASID.ASID := readtlbhi.asid
    }.otherwise {
      TLBIDX.NE := 1.U
      ASID.ASID := 0.U
      TLBEHI := 0.U.asTypeOf(TLBEHI)
      TLBELO0 := 0.U.asTypeOf(TLBELO0)
      TLBELO1 := 0.U.asTypeOf(TLBELO1)
      TLBIDX.PS := 0.U
    }
  }



  // redirect
  // NOTE : for brevity, make all csr related inst redirect
  val hasSideEffectOp = valid //&& (func === La32rCSROpType.csrwr || func === La32rCSROpType.csrxchg)

  io.redirect.valid := raiseException | retFromExcp | hasSideEffectOp | isIdle
  io.redirect.rtype := 0.U // TODO : what is this
  io.redirect.target := Mux(retFromExcp, ERA, Mux(raiseException, exceptionEntry, Mux(isIdle, io.cfIn.pc, io.cfIn.pc + 4.U)))


  io.in.ready := (cacop_state === cacop_idle)
  io.out.valid := (valid && !isCacop) || (isCacop && cacop_state === cacop_done) || (isCacop && raiseException)


  // perfcnt
  val generalPerfCntList = Map(
    "Mcycle" -> (0xb00, "perfCntCondMcycle"),
    "Minstret" -> (0xb02, "perfCntCondMinstret"),
    "MultiCommit" -> (0xb03, "perfCntCondMultiCommit"),
    "MimemStall" -> (0xb04, "perfCntCondMimemStall"),
    "MaluInstr" -> (0xb05, "perfCntCondMaluInstr"),
    "MbruInstr" -> (0xb06, "perfCntCondMbruInstr"),
    "MlsuInstr" -> (0xb07, "perfCntCondMlsuInstr"),
    "MmduInstr" -> (0xb08, "perfCntCondMmduInstr"),
    "McsrInstr" -> (0xb09, "perfCntCondMcsrInstr"),
    "MloadInstr" -> (0xb0a, "perfCntCondMloadInstr"),
    "MmmioInstr" -> (0xb0b, "perfCntCondMmmioInstr"),
    "MicacheHit" -> (0xb0c, "perfCntCondMicacheHit"),
    "MdcacheHit" -> (0xb0d, "perfCntCondMdcacheHit"),
    "MmulInstr" -> (0xb0e, "perfCntCondMmulInstr"),
    "MifuFlush" -> (0xb0f, "perfCntCondMifuFlush"),
    "MbpBRight" -> (0xb10, "MbpBRight"),
    "MbpBWrong" -> (0xb11, "MbpBWrong"),
    "MbpJRight" -> (0xb12, "MbpJRight"),
    "MbpJWrong" -> (0xb13, "MbpJWrong"),
    "MbpIRight" -> (0xb14, "MbpIRight"),
    "MbpIWrong" -> (0xb15, "MbpIWrong"),
    "MbpRRight" -> (0xb16, "MbpRRight"),
    "MbpRWrong" -> (0xb17, "MbpRWrong"),
    "Ml2cacheHit" -> (0xb18, "perfCntCondMl2cacheHit"),
    "Custom1" -> (0xb19, "Custom1"),
    "Custom2" -> (0xb1a, "Custom2"),
    "Custom3" -> (0xb1b, "Custom3"),
    "Custom4" -> (0xb1c, "Custom4"),
    "Custom5" -> (0xb1d, "Custom5"),
    "Custom6" -> (0xb1e, "Custom6"),
    "Custom7" -> (0xb1f, "Custom7"),
    "Custom8" -> (0xb20, "Custom8")
  )

  val sequentialPerfCntList = Map(
    "MrawStall" -> (0xb31, "perfCntCondMrawStall"),
    "MexuBusy" -> (0xb32, "perfCntCondMexuBusy"),
    "MloadStall" -> (0xb33, "perfCntCondMloadStall"),
    "MstoreStall" -> (0xb34, "perfCntCondMstoreStall"),
    "ISUIssue" -> (0xb35, "perfCntCondISUIssue")
  )
  val perfCntList = generalPerfCntList ++ sequentialPerfCntList

  val perfCntCond = List.fill(0x80)(WireInit(false.B))
  (perfCnts zip perfCntCond).map { case (c, e) => {
    when(e) {
      c := c + 1.U
    }
  }
  }

  BoringUtils.addSource(WireInit(true.B), "perfCntCondMcycle")
  perfCntList.foreach { case (name, (addr, boringId)) => {
    BoringUtils.addSink(perfCntCond(addr & 0x7f), boringId)
    if (!hasPerfCnt) {
      // do not enable perfcnts except for Mcycle and Minstret
      if (addr != perfCntList("Mcycle")._1 && addr != perfCntList("Minstret")._1) {
        perfCntCond(addr & 0x7f) := false.B
      }
    }
  }
  }

  val eulacoretrap = WireInit(false.B)
  BoringUtils.addSink(eulacoretrap, "eulacoretrap")

  def readWithScala(addr: Int): UInt = mapping(addr)._1

  if (!p.FPGAPlatform) {
    // to monitor
    BoringUtils.addSource(readWithScala(perfCntList("Mcycle")._1), "simCycleCnt")
    BoringUtils.addSource(readWithScala(perfCntList("Minstret")._1), "simInstrCnt")

    if (hasPerfCnt) {
      // display all perfcnt when nutcoretrap is executed
      val PrintPerfCntToCSV = true
      when(eulacoretrap) {
        if (Settings.get("EnablePerfOutput")) {
          printf("======== PerfCnt =========\n")
          perfCntList.toSeq.sortBy(_._2._1).map { case (name, (addr, boringId)) =>
            printf("%d <- " + name + "\n", readWithScala(addr))
          }
          if (PrintPerfCntToCSV) {
            printf("======== PerfCntCSV =========\n\n")
            perfCntList.toSeq.sortBy(_._2._1).map { case (name, (addr, boringId)) =>
              printf(name + ", ")
            }
            printf("\n\n\n")
            perfCntList.toSeq.sortBy(_._2._1).map { case (name, (addr, boringId)) =>
              printf("%d, ", readWithScala(addr))
            }
            printf("\n\n\n")
          }
        }
      }
    }

    val difftest = Module(new DifftestLa32rCSRState)
    difftest.io.clock := clock
    difftest.io.coreid := 0.U
    difftest.io.crmd := RegNext(CRMD.asUInt)
    difftest.io.prmd := RegNext(PRMD.asUInt)
    difftest.io.euen := RegNext(EUEN)
    difftest.io.ecfg := RegNext(ECFG)
    difftest.io.estat := RegNext(ESTAT.asUInt())
    difftest.io.era := RegNext(ERA)
    difftest.io.badv := RegNext(BADV)
    difftest.io.eentry := RegNext(EENTRY)
    difftest.io.tlbidx := RegNext(TLBIDX.asUInt())
    difftest.io.tlbehi := RegNext(TLBEHI.asUInt())
    difftest.io.tlbelo0 := RegNext(TLBELO0.asUInt())
    difftest.io.tlbelo1 := RegNext(TLBELO1.asUInt())
    difftest.io.asid := RegNext(ASID.asUInt())
    difftest.io.pgdl := RegNext(PGDL)
    difftest.io.pgdh := RegNext(PGDH)
    difftest.io.pgd := RegNext(PGD)
    difftest.io.cpuid := RegNext(CPUID)
    difftest.io.save0 := RegNext(SAVE0)
    difftest.io.save1 := RegNext(SAVE1)
    difftest.io.save2 := RegNext(SAVE2)
    difftest.io.save3 := RegNext(SAVE3)
    difftest.io.tid := RegNext(TID)
    difftest.io.tcfg := RegNext(TCFG)
    difftest.io.tval := RegNext(TVAL)
    difftest.io.ticlr := RegNext(TICLR)
    difftest.io.llbctl := RegNext(LLBCTL.asUInt())
    difftest.io.tlbrentry := RegNext(TLBRENTRY)
    difftest.io.dmw0 := RegNext(DMW0)
    difftest.io.dmw1 := RegNext(DMW1)

    val diffTimerSync = Module(new DifftestLa32rTimerState)
    diffTimerSync.io.clock := clock
    diffTimerSync.io.coreid := 0.U
    diffTimerSync.io.counter_id := RegNext(TID)
    diffTimerSync.io.stable_counter_l := RegNext(RegNext(stableCounter(31, 0)))
    diffTimerSync.io.stable_counter_h := RegNext(RegNext(stableCounter(63, 32)))
    diffTimerSync.io.time_val := RegNext(RegNext(TVAL))

    val diffEstatSync = Module(new DifftestLa32rEstatState)
    diffEstatSync.io.clock := clock
    diffEstatSync.io.coreid := 0.U
    diffEstatSync.io.estat := RegNext(ESTAT.asUInt())
    val needSyncEstat = io.instrValid && ((func === La32rCSROpType.excption && excptionNO === INT.U) || clearTimerInterrupt || (valid && func === La32rCSROpType.csrrd && addr === 5.U))
    diffEstatSync.io.wmask := RegNext(RegNext(Fill(32, needSyncEstat)))

  }
  io.difftestExceptionSkip := io.instrValid && raiseException && excptionNO === INT.U

  io.tlbModifyInst := valid && func === La32rCSROpType.tlbwr || func === La32rCSROpType.tlbfill || func === La32rCSROpType.invtlb

}
