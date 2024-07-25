package eulacore.backend

import bus.simplebus._
import chisel3._
import chisel3.util._
import eulacore._
import utils._

class Backend_inorder(implicit val p: EulaCoreConfig) extends EulaCoreModule {
  val io = IO(new Bundle {
    val in = Vec(2, Flipped(Decoupled(new DecodeIO)))
    val flush = Input(UInt(2.W))
    val dmem = new SimpleBusUC(addrBits = VAddrBits, userBits = dmmuUserBits)

    val redirect = new RedirectIO
  })

  val isu  = Module(new ISU)
  val exu  = Module(new EXU)
  val wbu  = Module(new WBU)

  PipelineConnect(isu.io.out, exu.io.in, exu.io.out.fire(), io.flush(0))
  PipelineConnect(exu.io.out, wbu.io.in, true.B, io.flush(1))

  isu.io.in <> io.in
  
  isu.io.flush := io.flush(0)
  exu.io.flush := io.flush(1)

  isu.io.wb <> wbu.io.wb
  io.redirect <> wbu.io.redirect
  // forward
  isu.io.forward <> exu.io.forward  

  io.dmem <> exu.io.dmem
}