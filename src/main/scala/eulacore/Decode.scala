package eulacore

import chisel3._
import chisel3.util._

trait HasLa32rInstrType {
  def Instr2R     = "b0000".U
  def Instr3R     = "b0001".U
//  def Instr4R     = "b0010".U
  def Instr2RI8   = "b0011".U
  def Instr2RI12  = "b0100".U
  def Instr2RI14  = "b0101".U
  def Instr2RI16  = "b0110".U
  def Instr1RI21  = "b0111".U
  def InstrI26    = "b1000".U
  def Instr1RI20  = "b1001".U
  def Instr2RI5   = "b1010".U
  def InstrBranch = "b1011".U
  def InstrStore  = "b1100".U
  def Instr2RI12ZEXT = "b1101".U
  def InstrCODE15 = "b1110".U
  def InstrN      = "b1111".U // the instruction is not a legal instr
}


object SrcType {
  def reg = "b0".U
  def pc  = "b1".U
  def imm = "b1".U
  def apply() = UInt(1.W)
}

object FuType extends HasEulaCoreConst {
  def num = 5
  def alu = "b000".U
  def lsu = "b001".U
  def mdu = "b010".U
  def csr = "b011".U
  def mou = "b100".U
  def bru = if(IndependentBru) "b101".U
            else               alu
  def apply() = UInt(log2Up(num).W)
}

object FuOpType {
  def apply() = UInt(7.W)
}

object La32rInstructions extends HasLa32rInstrType with HasEulaCoreParameter {
  def NOP = "b0000001101_000000000000_00000_00000".U // andi r0, r0, 0
  val DecodeDefault = List(InstrN, FuType.csr, La32rCSROpType.excption, false.B)
  def DecodeTable = LA32R_ALUInstr.table ++ LA32R_BRUInstr.table ++
    LA32R_LSUInstr.table ++ LA32R_CSRInstr.table ++ LA32R_MDUInstr.table ++ LA32R_MOUInstr.table
}