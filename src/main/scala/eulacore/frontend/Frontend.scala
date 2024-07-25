package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import bus.simplebus._
import chisel3.experimental.IO

class FrontendIO(implicit val p: EulaCoreConfig) extends Bundle with HasEulaCoreConst {
  val imem = new SimpleBusUC(userBits = immuUserBits, addrBits = VAddrBits)
  val out = Vec(2, Decoupled(new DecodeIO))
  val flushVec = Output(UInt(4.W))
  val redirect = Flipped(new RedirectIO)
  val bpFlush = Output(Bool())
}


trait HasFrontendIO {
  implicit val p: EulaCoreConfig
  val io = IO(new FrontendIO)
}

class Frontend_embedded(implicit val p: EulaCoreConfig) extends EulaCoreModule with HasFrontendIO {
  val ifu  = Module(new IFU_embedded)
  val idu  = Module(new IDU)

  PipelineConnect(ifu.io.out, idu.io.in(0), idu.io.out(0).fire(), ifu.io.flushVec(0))
  idu.io.in(1) := DontCare

  io.out <> idu.io.out
  io.redirect <> ifu.io.redirect
  io.flushVec <> ifu.io.flushVec
  io.bpFlush <> ifu.io.bpFlush
  io.imem <> ifu.io.imem

}