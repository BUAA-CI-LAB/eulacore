package eulacore.mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import bus.simplebus._
import bus.axi4._
import chisel3.experimental.IO
import utils._
import top.Settings
import difftest._
import eulacore._

trait HasMemAccessMaster {
  val FETCH = 1.U
  val LOAD = 2.U
  val STORE = 3.U
}

trait HasLa32rTLBConst extends HasEulaCoreParameter {

  val valen = VAddrBits
  val palen = PAddrBits
  val vppnlen = valen - 13
  val ppnlen = palen - 12

  class TLBHIStruct extends Bundle {
    val vppn = Output(UInt(vppnlen.W))
    val ps = Output(UInt(6.W))
    val g = Output(UInt(1.W))
    val asid = Output(UInt(10.W))
    val e = Output(UInt(1.W))
  }

  class TLBLOStruct extends Bundle {
    val ppn = Output(UInt(ppnlen.W))
    val plv = Output(UInt(2.W))
    val mat = Output(UInt(2.W))
    val d = Output(UInt(1.W))
    val v = Output(UInt(1.W))
  }

  val TLBHIStructBits = (new TLBHIStruct).getWidth
  val TLBLOStructBits = (new TLBLOStruct).getWidth

}

class La32rTLBExcpIO extends Bundle  {
  val tlbRefillExcp = Output(Bool())  // TLBR
  val fetchPageInvalidExcp = Output(Bool()) // PIF
  val loadPageInvalidExcp = Output(Bool()) // PIL
  val storePageInvalidExcp = Output(Bool()) // PIS
  val pagePrivInvalidExcp = Output(Bool()) // PPI
  val pageModifyExcp = Output(Bool()) // PME
}

class La32rTLBCSRReadlIO(implicit val la32rMMUConfig: La32rMMUConfig) extends EulaCoreBundle with HasLa32rTLBConst with HasLa32rMMUConst {

  val readIdx = Input(UInt(log2Up(tlbEntryNum).W)) // used for tlbrd

  val hit = Output(Bool()) // used for tlbsrch
  val hitIdx = Output(UInt(log2Up(tlbEntryNum).W)) // used for tlbsrch
  val readTlbHi = Output(UInt(TLBHIStructBits.W))
  val readTlbLo0 = Output(UInt(TLBLOStructBits.W))
  val readTlbLo1 = Output(UInt(TLBLOStructBits.W))
}

class La32rTLBIO(implicit val la32rMMUConfig: La32rMMUConfig) extends Bundle with HasLa32rTLBConst with HasLa32rMMUConst {
  val in = Flipped(ValidIO(new Bundle {
    val vaddr = Output(UInt(valen.W))
    val memAccessMaster = Output(UInt(2.W))
  }))
  val out = ValidIO(new Bundle {
    val excp = new La32rTLBExcpIO
    val paddr = Output(UInt(palen.W))
    val mat = Output(UInt(2.W))
  })

  val csrread = if (mmuname == "csr") Some(new La32rTLBCSRReadlIO) else None
}

class La32rTLBEntry(implicit val la32rMMUConfig: La32rMMUConfig) extends EulaCoreBundle with HasLa32rTLBConst {
  val tlbhi = new TLBHIStruct
  val tlblo0 = new TLBLOStruct
  val tlblo1 = new TLBLOStruct
}

class La32rTLB(implicit val la32rMMUConfig: La32rMMUConfig) extends EulaCoreModule with HasLa32rTLBConst with HasLa32rCSRConst with HasLa32rMMUConst with HasMemAccessMaster {

  val io = IO(new La32rTLBIO())

  val tlbEntrys = RegInit(0.U.asTypeOf(Vec(tlbEntryNum, new La32rTLBEntry())))

  val ASID = WireInit(0.U(32.W))
  BoringUtils.addSink(ASID, "ASID")
  val asidStruct = ASID.asTypeOf(new ASIDStruct)
  val CRMD = WireInit(0.U(32.W))
  BoringUtils.addSink(CRMD, "CRMD")
  val crmdStruct = CRMD.asTypeOf(new CRMDStruct)

  // tlb search

  def tlbHitCheck(vaddr: UInt, asid: UInt, tlbEntry: La32rTLBEntry) = {
    val tlbhi = tlbEntry.tlbhi
    val tlblo0 = tlbEntry.tlblo0
    val tlblo1 = tlbEntry.tlblo1
    val isSmallPage = tlbhi.ps === 12.U // ps can only be 12 or 21
    val fixvaddr = Mux(isSmallPage, vaddr(valen - 1, 13), vaddr(valen - 1, 22))
    val fixvppn = Mux(isSmallPage, tlbhi.vppn(vppnlen - 1, 0), tlbhi.vppn(vppnlen - 1, 9))
    val tlb_found = tlbhi.e === 1.U && (tlbhi.g === 1.U || tlbhi.asid === asid) && fixvaddr === fixvppn
    val vaddrSelectBit = Mux(isSmallPage, vaddr(12), vaddr(21))
    val selectTlblo = Mux(vaddrSelectBit, tlblo1, tlblo0)
    (tlb_found, selectTlblo, tlbhi)
  }

  val vaddr = io.in.bits.vaddr
  val hitResult = tlbEntrys.map(entry => tlbHitCheck(vaddr, asidStruct.ASID, entry))
  val hitVec = VecInit(hitResult.map(i => i._1))
  val hitTlbLo = VecInit(hitResult.map(i => Fill(i._2.asUInt().getWidth, i._1) & i._2.asUInt())).reduce(_|_).asTypeOf(new TLBLOStruct)
  val hitTlbHi = VecInit(hitResult.map(i => Fill(i._3.asUInt().getWidth, i._1) & i._3.asUInt())).reduce(_|_).asTypeOf(new TLBHIStruct)

  val isSmallPage = hitTlbHi.ps === 12.U

  val paddr = Mux(isSmallPage, Cat(hitTlbLo.ppn, vaddr(11 ,0)), Cat(hitTlbLo.ppn(ppnlen - 1, 9), vaddr(20, 0)))

  when (io.in.valid && PopCount(hitVec) > 1.U) {
    printf("multiple hit in tlb!\n")
  }

  io.out.valid := io.in.valid
  io.out.bits.paddr := paddr
  io.out.bits.mat := hitTlbLo.mat

  // tlb excp
  // handle tlbr excp
  val tlb_found = io.in.valid && hitVec.reduce(_||_)
  io.out.bits.excp.tlbRefillExcp := !tlb_found

  // handle pif excp
  io.out.bits.excp.fetchPageInvalidExcp := tlb_found && io.in.bits.memAccessMaster === FETCH && hitTlbLo.v === 0.U

  // handle pil excp
  io.out.bits.excp.loadPageInvalidExcp := tlb_found && io.in.bits.memAccessMaster === LOAD && hitTlbLo.v === 0.U

  // handle pis excp
  io.out.bits.excp.storePageInvalidExcp := tlb_found && io.in.bits.memAccessMaster === STORE && hitTlbLo.v === 0.U

  // handle ppi excp
  io.out.bits.excp.pagePrivInvalidExcp := tlb_found && hitTlbLo.v === 1.U && crmdStruct.PLV > hitTlbLo.plv

  // handle pme excp
  io.out.bits.excp.pageModifyExcp := tlb_found && hitTlbLo.v === 1.U && crmdStruct.PLV <= hitTlbLo.plv && io.in.bits.memAccessMaster === STORE && hitTlbLo.d === 0.U


  // tlb modify
  val tlbModifyValid = WireInit(false.B)
  val tlbModifyOp = WireInit(0.U(7.W))
  val invtlbOp = WireInit(0.U(5.W))
  val invtlbasid = WireInit(0.U(10.W))
  val invtlbvaddr = WireInit(0.U(32.W))
  val TLBEHI , TLBELO0, TLBELO1, TLBIDX, ESTAT, pc = WireInit(0.U(32.W))
  BoringUtils.addSink(tlbModifyValid, "tlbModifyValid")
  BoringUtils.addSink(tlbModifyOp, "tlbModifyOp")
  BoringUtils.addSink(invtlbOp, "invtlbOp")
  BoringUtils.addSink(invtlbasid, "invtlbasid")
  BoringUtils.addSink(invtlbvaddr, "invtlbvaddr")
  BoringUtils.addSink(TLBEHI, "TLBEHI")
  BoringUtils.addSink(TLBELO0, "TLBELO0")
  BoringUtils.addSink(TLBELO1, "TLBELO1")
  BoringUtils.addSink(TLBIDX, "TLBIDX")
  BoringUtils.addSink(ESTAT, "ESTAT")
  BoringUtils.addSink(pc, "tlbDebugPC")
  val tlbehiStruct = TLBEHI.asTypeOf(new TLBEHIStruct)
  val tlbelo0Struct = TLBELO0.asTypeOf(new TLBELOStruct)
  val tlbelo1Struct = TLBELO1.asTypeOf(new TLBELOStruct)
  val tlbidxStruct = TLBIDX.asTypeOf(new TLBIDXStruct)
  val estatStruct = ESTAT.asTypeOf(new ESTATStruct)

  val tlbhiWrite = WireInit(0.U.asTypeOf(new TLBHIStruct))
  val tlblo0Write, tlblo1Write = WireInit(0.U.asTypeOf(new TLBLOStruct))

  val tlbloWriteVec = Seq(tlblo0Write, tlblo1Write)
  val tlbloStructVec = Seq(tlbelo0Struct, tlbelo1Struct)

  val tlbFillIdx = RegInit(0.U(log2Up(tlbEntryNum).W))
  tlbFillIdx := tlbFillIdx + 1.U

  when (tlbModifyValid && (tlbModifyOp === La32rCSROpType.tlbwr || tlbModifyOp === La32rCSROpType.tlbfill)) {
    tlbhiWrite.vppn := tlbehiStruct.VPPN
    tlbhiWrite.ps := tlbidxStruct.PS
    tlbhiWrite.g := tlbelo0Struct.G === 1.U && tlbelo1Struct.G === 1.U
    tlbhiWrite.asid := asidStruct.ASID
    tlbhiWrite.e := estatStruct.Ecode === 0x3F.U || tlbidxStruct.NE === 0.U

    tlbloWriteVec.zip(tlbloStructVec).foreach { case (tlbloWrite, tlbloStruct) =>
      tlbloWrite.ppn := tlbloStruct.PPN
      tlbloWrite.plv := tlbloStruct.PLV
      tlbloWrite.mat := tlbloStruct.MAT
      tlbloWrite.d := tlbloStruct.D
      tlbloWrite.v := tlbloStruct.V
    }

    val tlbWriteIdx = Mux(tlbModifyOp === La32rCSROpType.tlbwr, tlbidxStruct.index, tlbFillIdx)
    tlbEntrys(tlbWriteIdx).tlbhi := tlbhiWrite
    tlbEntrys(tlbWriteIdx).tlblo0 := tlblo0Write
    tlbEntrys(tlbWriteIdx).tlblo1 := tlblo1Write

//    printf("tlbmodify:timer=0x%x,pc=0x%x,func=%d,idx=%d,tlbhi=0x%x,tlblo0=0x%x,tlblo1=0x%x\n",
//      GTimer(),pc,tlbModifyOp,tlbWriteIdx,tlbhiWrite.asUInt(),tlblo0Write.asUInt(),tlblo1Write.asUInt())
  }

  when (tlbModifyValid && tlbModifyOp === La32rCSROpType.invtlb) {
    tlbEntrys.foreach { i =>
      val isSmallPage = i.tlbhi.ps === 12.U // ps can only be 12 or 21
      val fixvaddr = Mux(isSmallPage, invtlbvaddr(valen - 1, 13), invtlbvaddr(valen - 1, 22))
      val fixvppn = Mux(isSmallPage, i.tlbhi.vppn(vppnlen - 1, 0), i.tlbhi.vppn(vppnlen - 1, 9))
      when (invtlbOp === 0.U || invtlbOp === 1.U ||
        (invtlbOp === 2.U && i.tlbhi.g === 1.U) ||
        (invtlbOp === 3.U && i.tlbhi.g === 0.U) ||
        (invtlbOp === 4.U && i.tlbhi.g === 0.U && i.tlbhi.asid === invtlbasid) ||
        (invtlbOp === 5.U && i.tlbhi.g === 0.U && i.tlbhi.asid === invtlbasid && fixvaddr === fixvppn) ||
        (invtlbOp === 6.U && (i.tlbhi.g === 1.U || i.tlbhi.asid === invtlbasid) && fixvaddr === fixvppn)) {
        i.tlbhi.e := false.B // align with nemu
//        i.tlbhi := 0.U.asTypeOf(new TLBHIStruct)
//        i.tlblo0 := 0.U.asTypeOf(new TLBLOStruct)
//        i.tlblo1 := 0.U.asTypeOf(new TLBLOStruct)
      }
    }
  }

  // csread
  if (mmuname == "csr") {
    val csrread = io.csrread.get
    csrread.hit := tlb_found
    csrread.hitIdx := OHToUInt(hitVec)
    csrread.readTlbHi := tlbEntrys(csrread.readIdx).tlbhi.asUInt()
    csrread.readTlbLo0 := tlbEntrys(csrread.readIdx).tlblo0.asUInt()
    csrread.readTlbLo1 := tlbEntrys(csrread.readIdx).tlblo1.asUInt()
  }

  if (!la32rMMUConfig.FPGAPlatform && mmuname == "csr") { // only diff csr tlb because itlb,dtlb,csrtlb is equal at any time
    val diffTlbFillIdxSync = Module(new DifftestLa32rTlbFillIndexSet)
    diffTlbFillIdxSync.io.clock := clock
    diffTlbFillIdxSync.io.coreid := 0.U
    diffTlbFillIdxSync.io.valid := tlbModifyValid && tlbModifyOp === La32rCSROpType.tlbfill
    diffTlbFillIdxSync.io.index := tlbFillIdx

    (0 until tlbEntryNum).foreach{ i =>
      val diffTlbEntry = Module(new DifftestLa32rTlbEntry)
      diffTlbEntry.io.clock := clock
      diffTlbEntry.io.index := i.U
      diffTlbEntry.io.coreid := 0.U
      diffTlbEntry.io.entryhi := tlbEntrys(i).tlbhi.asUInt()
      diffTlbEntry.io.entrylo0 := tlbEntrys(i).tlblo0.asUInt()
      diffTlbEntry.io.entrylo1 := tlbEntrys(i).tlblo1.asUInt()
    }
  }


}

object La32rTLB {
  def apply()(implicit la32rMMUConfig: La32rMMUConfig) = {
    val tlb = Module(new La32rTLB())
    tlb
  }
}
