package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import difftest._

abstract class AbstractDecoder(implicit val p: EulaCoreConfig) extends EulaCoreModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new CtrlFlowIO))
    val out = Decoupled(new DecodeIO)
    val isBranch = Output(Bool())
  })
}

class La32rDecoder(implicit override val p: EulaCoreConfig) extends AbstractDecoder with HasLa32rInstrType {

  val hasIntr = Wire(Bool())
  val instr = io.in.bits.instr
  val decodeList = ListLookup(instr, La32rInstructions.DecodeDefault, La32rInstructions.DecodeTable)
  val instrType :: fuType :: fuOpType :: isrfWen :: Nil =
    La32rInstructions.DecodeDefault.zip(decodeList).map { case (intr, dec) => Mux(hasIntr, intr, dec) }


  val outFuOpType = io.out.bits.ctrl.fuOpType // TODO : this is dirty implementation to pass firrtl compile
  io.out.bits := DontCare
  io.isBranch := La32rALUOpType.isBranch(outFuOpType) && fuType === FuType.bru // TODO : this signal may be useless

  io.out.bits.ctrl.fuType := fuType
  io.out.bits.ctrl.fuOpType := fuOpType

  // use SrcType.imm when we do not need reg source
  // rj is src1, rk is src2, rd is dest
  // but when it is branch inst, rj is src1, rd is src2
  val SrcTypeTable = List(
    Instr2R     -> (SrcType.reg, SrcType.imm),
    Instr3R     -> (SrcType.reg, SrcType.reg),
    Instr2RI8   -> (SrcType.reg, SrcType.imm),
    Instr2RI12  -> (SrcType.reg, SrcType.imm),
    Instr2RI14  -> (SrcType.reg, SrcType.imm),
    Instr2RI16  -> (SrcType.reg, SrcType.imm),
    Instr1RI21  -> (SrcType.reg, SrcType.imm),
    InstrI26    -> (SrcType.imm, SrcType.imm),
    Instr1RI20  -> (SrcType.imm, SrcType.imm),
    Instr2RI5   -> (SrcType.reg, SrcType.imm),
    InstrBranch -> (SrcType.reg, SrcType.reg),
    InstrStore  -> (SrcType.reg, SrcType.reg),
    Instr2RI12ZEXT -> (SrcType.reg, SrcType.imm),
    InstrN      -> (SrcType.imm, SrcType.imm),
    InstrCODE15 -> (SrcType.imm, SrcType.imm),
  )

  val src1Type = LookupTree(instrType, SrcTypeTable.map(p => (p._1, p._2._1)))
  val src2Type = LookupTree(instrType, SrcTypeTable.map(p => (p._1, p._2._2)))

  val (rk, rj, rd) = (instr(14, 10), instr(9, 5), instr(4, 0))
  val rfSrc1 = rj
  val rfSrc2 = Mux(fuType === FuType.bru && La32rALUOpType.isBranch(outFuOpType)
    || fuType === FuType.lsu && La32rLSUOpType.isStore(outFuOpType), rd, rk)
  val rfDest = Mux(fuType === FuType.csr && fuOpType === La32rCSROpType.rdcntid, rj,
    Mux(fuType === FuType.bru && (fuOpType === La32rALUOpType.call), 1.U ,rd))

  io.out.bits.ctrl.rfSrc1 := Mux(src1Type === SrcType.imm , 0.U, rfSrc1)
  io.out.bits.ctrl.rfSrc2 := Mux(src2Type === SrcType.imm , 0.U, rfSrc2)
  io.out.bits.ctrl.rfWen := isrfWen
  io.out.bits.ctrl.rfDest := Mux(isrfWen.asBool, rfDest, 0.U)


  val imm = LookupTree(instrType, List(
    Instr2RI8     -> SignExt(instr(17, 10), XLEN),
    Instr2RI12    -> SignExt(instr(21, 10), XLEN),
    Instr2RI14    -> SignExt(instr(23, 10), XLEN),
    Instr2RI16    -> SignExt(instr(25, 10), XLEN),
    Instr1RI21    -> SignExt(Cat(instr(4, 0), instr(25, 10)), XLEN),
    InstrI26      -> SignExt(Cat(instr(9, 0), instr(25, 10)), XLEN),
    Instr1RI20    -> SignExt(instr(24, 5), XLEN),
    Instr2RI5     -> ZeroExt(instr(14, 10), XLEN),
    InstrBranch   -> SignExt(instr(25, 10), XLEN),
    InstrStore    -> SignExt(instr(21, 10), XLEN),
    Instr2RI12ZEXT -> ZeroExt(instr(21, 10), XLEN),
    InstrCODE15   -> ZeroExt(instr(14, 0), XLEN),
  ))
  io.out.bits.data.imm := imm

  io.out.bits.ctrl.src1Type := src1Type
  io.out.bits.ctrl.src2Type := src2Type

  when (fuType === FuType.bru) {
    // see la32r spec page 17 JIRL description
    when (fuOpType === La32rALUOpType.jirl && rd === 0.U && rj === 1.U && instr(25, 10) === 0.U) {
      io.out.bits.ctrl.fuOpType := La32rALUOpType.ret
    }
  }

  // fix csr access inst
  when (fuType === FuType.csr && fuOpType === La32rCSROpType.csracc) {
    val csrrj = instr(9, 5)
    when (csrrj === 0.U) {
      io.out.bits.ctrl.fuOpType := La32rCSROpType.csrrd
      io.out.bits.ctrl.src1Type := SrcType.imm
      io.out.bits.ctrl.src2Type := SrcType.imm
    }.elsewhen(csrrj === 1.U) {
      io.out.bits.ctrl.fuOpType := La32rCSROpType.csrwr
      io.out.bits.ctrl.rfSrc2 := rd
      io.out.bits.ctrl.src1Type := SrcType.imm
      io.out.bits.ctrl.src2Type := SrcType.reg
    }.otherwise {
      io.out.bits.ctrl.fuOpType := La32rCSROpType.csrxchg
      io.out.bits.ctrl.rfSrc1 := rj
      io.out.bits.ctrl.rfSrc2 := rd
      io.out.bits.ctrl.src1Type := SrcType.reg
      io.out.bits.ctrl.src2Type := SrcType.reg
    }
  }

  io.out.valid := io.in.valid
  io.in.ready := !io.in.valid || io.out.fire() && !hasIntr
  io.out.bits.cf <> io.in.bits

  LADebug(io.out.fire, "[IDU] pc=0x%x,instr=0x%x,fuType=%d,fuOpType=%d,rfSrc1=%d,rfSrc2=%d,imm=0x%x,src1Type=%d,src2Type=%d,rd=%d,rj=%d,rk=%d\n",
    io.in.bits.pc, io.in.bits.instr, io.out.bits.ctrl.fuType, io.out.bits.ctrl.fuOpType, io.out.bits.ctrl.rfSrc1,
    io.out.bits.ctrl.rfSrc2, io.out.bits.data.imm, io.out.bits.ctrl.src1Type, io.out.bits.ctrl.src2Type, rd, rj, rk)

  val intrVec = WireInit(0.U(13.W))
  BoringUtils.addSink(intrVec, "intrVecIDU")
  io.out.bits.cf.intrVec.zip(intrVec.asBools).map { case (x, y) => x := y }
  hasIntr := intrVec.orR

  val fetchHasExcp = io.in.bits.exceptionVec(ADEF) | io.in.bits.exceptionVec(TLBR) | io.in.bits.exceptionVec(PIF) | io.in.bits.exceptionVec(PPI)

  io.out.bits.cf.exceptionVec := io.in.bits.exceptionVec
  io.out.bits.cf.exceptionVec(INT) := io.in.valid && hasIntr
  io.out.bits.cf.exceptionVec(INE) := (instrType === InstrN && !hasIntr) && io.in.valid && !fetchHasExcp

  io.out.bits.ctrl.isEulaCoreTrap := (instr === EulaCoreTrap.TRAP) && io.in.valid

}

class IDU(implicit val p: EulaCoreConfig) extends EulaCoreModule {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(Decoupled(new CtrlFlowIO)))
    val out = Vec(2, Decoupled(new DecodeIO))
  })
  val decoder1  = Module(new La32rDecoder)
  val decoder2  = Module(new La32rDecoder)
  io.in(0) <> decoder1.io.in
  io.in(1) <> decoder2.io.in
  io.out(0) <> decoder1.io.out
  io.out(1) <> decoder2.io.out

    io.in(1).ready := false.B
    decoder2.io.in.valid := false.B

  io.out(0).bits.cf.isBranch := decoder1.io.isBranch

}
