package eulacore

import chisel3._
import chisel3.util._
import eulacore.mem.La32rTLBExcpIO

class CtrlSignalIO extends EulaCoreBundle {
  val src1Type = Output(SrcType())
  val src2Type = Output(SrcType())
  val fuType = Output(FuType())
  val fuOpType = Output(FuOpType())
  val rfSrc1 = Output(UInt(5.W))
  val rfSrc2 = Output(UInt(5.W))
  val rfWen = Output(Bool())
  val rfDest = Output(UInt(5.W))
  val isEulaCoreTrap = Output(Bool())
  val isSrc1Forward = Output(Bool())
  val isSrc2Forward = Output(Bool())
}

class DataSrcIO extends EulaCoreBundle {
  val src1 = Output(UInt(XLEN.W))
  val src2 = Output(UInt(XLEN.W))
  val imm  = Output(UInt(XLEN.W))
}

class RedirectIO extends EulaCoreBundle {
  val target = Output(UInt(VAddrBits.W))
  val rtype = Output(UInt(1.W)) // 1: branch mispredict: only need to flush frontend  0: others: flush the whole pipeline
  val valid = Output(Bool())
}

class CtrlFlowIO extends EulaCoreBundle {
  val instr = Output(UInt(64.W))
  val pc = Output(UInt(VAddrBits.W))
  val pnpc = Output(UInt(VAddrBits.W))
  val redirect = new RedirectIO
  val exceptionVec = Output(Vec(16, Bool()))
  val intrVec = Output(Vec(13, Bool()))
  val brIdx = Output(UInt(4.W))
  val isBranch = Output(Bool())
}

class StoreCheckIO extends EulaCoreBundle {
  val valid = Output(Bool())
  val storeAddr = Output(UInt(64.W))
  val storeData = Output(UInt(64.W))
}

class DecodeIO extends EulaCoreBundle {
  val cf = new CtrlFlowIO
  val ctrl = new CtrlSignalIO
  val data = new DataSrcIO
}

class WriteBackIO extends EulaCoreBundle {
  val rfWen = Output(Bool())
  val rfDest = Output(UInt(5.W))
  val rfData = Output(UInt(XLEN.W))
}

class CommitIO extends EulaCoreBundle {
  val decode = new DecodeIO
  val isMMIO = Output(Bool())
  val intrNO = Output(UInt(XLEN.W))
  val commits = Output(Vec(FuType.num, UInt(XLEN.W)))
  val storeCheck = new StoreCheckIO
  val difftestExceptionSkip = Output(Bool()) // for interrupt difftest align
  val tlbModifyInst = Output(Bool())
}

class FunctionUnitIO extends EulaCoreBundle {
  val in = Flipped(Decoupled(new Bundle {
    val src1 = Output(UInt(XLEN.W))
    val src2 = Output(UInt(XLEN.W))
    val func = Output(FuOpType())
  }))
  val out = Decoupled(Output(UInt(XLEN.W)))
}

class ForwardIO extends EulaCoreBundle {
  val valid = Output(Bool())
  val wb = new WriteBackIO
  val fuType = Output(FuType())
}


class La32rLSUExcpIO extends EulaCoreBundle {
  val hasExcp = Output(Bool())
  val badv = Output(UInt(XLEN.W))
  val ale = Output(Bool())
  val tlbExcp = new La32rTLBExcpIO
}