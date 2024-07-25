package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import difftest._
import top.Settings


class ALUIO extends FunctionUnitIO {
  val cfIn = Flipped(new CtrlFlowIO)
  val redirect = new RedirectIO
  val offset = Input(UInt(XLEN.W)) // offset equals to imm, wire connect in EXU
}

abstract class AbstractALU(hasBru: Boolean = false) extends EulaCoreModule {
  val io = IO(new ALUIO)

  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)

  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }
}

object La32rALUOpType {
  def add   = 1.U
  def sub   = 2.U
  def lu12i = 3.U
  def slt   = 4.U
  def sltu  = 5.U
  def pcaddu12i = 6.U
  def and = 7.U
  def or = 8.U
  def nor = 9.U
  def xor = 10.U
  def sll = 11.U
  def srl = 12.U
  def sra = 13.U

  // control flow instruction
  // func(4) is always 1
  // branch (conditional jump) func(3) is always 0
  def beq   = "b0010000".U
  def bne   = "b0010001".U
  def blt   = "b0010010".U
  def bge   = "b0010011".U
  def bltu  = "b0010100".U
  def bgeu  = "b0010101".U

  def b     = "b0011000".U
  def jirl  = "b0011010".U
  def call  = "b0011011".U // call is bl
  def ret   = "b0011100".U // jirl & rd=0 & rj=1 & offs16=0


  def isBru(func: UInt) = func(4)
  def isBranch(func: UInt) = func(4) && !func(3) // branch === conditional cfi
  def isJump(func: UInt) = func(4) && func(3) // jump === unconditional cfi
}

// note : when src1 is not reg, it is pc, when src2 is not reg, it is imm, see ISU
class La32rALU(hasBru : Boolean = false) extends AbstractALU {

  val rj = src1
  val rk = src2
  val branchRd = src2
  val imm = io.offset

  val isAdderSub = func =/= La32rALUOpType.add
  val adderRes = (src1 +& (src2 ^ Fill(XLEN, isAdderSub))) + isAdderSub
  val orRes = src1 | src2
  val sllRes = src1 << src2(4, 0)
  val srlRes = src1 >> src2(4, 0)
  val sraRes = (src1.asSInt() >> src2(4, 0)).asUInt()

  val aluRes = LookupTreeDefault(func, adderRes, List(
    La32rALUOpType.add   -> adderRes,
    La32rALUOpType.sub   -> adderRes,
    La32rALUOpType.slt   -> (rj.asSInt() < rk.asSInt()),
    La32rALUOpType.sltu  -> (rj < rk),
    La32rALUOpType.nor   -> ~orRes,
    La32rALUOpType.and   -> (rj & rk),
    La32rALUOpType.or    -> orRes,
    La32rALUOpType.xor   -> (rj ^ rk),
    La32rALUOpType.sll   -> sllRes,
    La32rALUOpType.srl   -> srlRes,
    La32rALUOpType.sra   -> sraRes,
    La32rALUOpType.lu12i -> Cat(src2(19, 0), 0.U(12.W)),
    La32rALUOpType.pcaddu12i -> (src1 + Cat(src2(19, 0), 0.U(12.W))),
  ))

  val branchTakenTable = List(
    La32rALUOpType.beq -> (rj === branchRd),
    La32rALUOpType.bne -> (rj =/= branchRd),
    La32rALUOpType.blt -> (rj.asSInt() < branchRd.asSInt()),
    La32rALUOpType.bge -> (rj.asSInt() >= branchRd.asSInt()),
    La32rALUOpType.bltu -> (rj < branchRd),
    La32rALUOpType.bgeu -> (rj >= branchRd)
  )

  val isBru = La32rALUOpType.isBru(func)
  val isBranch = La32rALUOpType.isBranch(func)
  val branchTaken = LookupTree(func, branchTakenTable)
  val branchTakenTarget = io.cfIn.pc + SignExt(Cat(imm(15, 0), 0.U(2.W)), XLEN)
  val directJumpTarget = io.cfIn.pc + SignExt(Cat(imm(25, 0), 0.U(2.W)), XLEN) // B and BL use this
  val indirectJumpTarget = rj + SignExt(Cat(imm(15, 0), 0.U(2.W)), XLEN) // JIRL use this
  val taken = (isBranch && branchTaken) | La32rALUOpType.isJump(func)
  val takenTarget = Mux(isBranch, branchTakenTarget,
    Mux(func === La32rALUOpType.call || func === La32rALUOpType.b, directJumpTarget, indirectJumpTarget))
  val predictWrong = isBru && ((io.cfIn.brIdx(0) ^ taken) | (taken && io.cfIn.pnpc =/= takenTarget)) // direction fail or target fail

  LADebug(valid && predictWrong, "[BPW] instr=0x%x, pc=0x%x,taken=%d,redirectTarget=0x%x, indirectJumpTarget=0x%x, rj=0x%x\n",
    io.cfIn.instr, io.cfIn.pc, taken, io.redirect.target, indirectJumpTarget, rj)
  io.redirect.valid := valid && predictWrong
  io.redirect.target := Mux(taken, takenTarget, io.cfIn.pc + 4.U)
  val redirectRtype = 0.U // if (EnableOutOfOrderExec) 1.U else 0.U // TODO : what is this
  io.redirect.rtype := redirectRtype

  io.out.bits := Mux(isBru, io.cfIn.pc + 4.U,aluRes)

  io.in.ready := io.out.ready
  io.out.valid := valid

  val bruFuncTobtbTypeTable = List(
    La32rALUOpType.jirl -> BTBtype.I,
    La32rALUOpType.b -> BTBtype.J,
    La32rALUOpType.call -> BTBtype.J,
    La32rALUOpType.beq -> BTBtype.B,
    La32rALUOpType.bne -> BTBtype.B,
    La32rALUOpType.blt -> BTBtype.B,
    La32rALUOpType.bge -> BTBtype.B,
    La32rALUOpType.bltu -> BTBtype.B,
    La32rALUOpType.bgeu -> BTBtype.B
  )

  val bpuUpdateReq = WireInit(0.U.asTypeOf(new BPUUpdateReq))
  bpuUpdateReq.valid := valid && isBru
  bpuUpdateReq.pc := io.cfIn.pc
  bpuUpdateReq.isMissPredict := predictWrong
  bpuUpdateReq.actualTarget := io.redirect.target
  bpuUpdateReq.actualTaken := taken
  bpuUpdateReq.fuOpType := func
  bpuUpdateReq.btbType := LookupTree(func, bruFuncTobtbTypeTable)

  if (hasBru) {
    BoringUtils.addSource(RegNext(bpuUpdateReq), "bpuUpdateReq")
  }
}